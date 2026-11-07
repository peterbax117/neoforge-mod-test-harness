package io.github.peterbax117.modtestharness.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ClientHooks;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Handed to every {@link ClientTest}. Tests run on a background thread; every method here hops onto the
 * client or integrated server thread and waits for the result, so test code reads top to bottom.
 * Input goes through the game's own paths: key mappings, the keyboard handler and NeoForge's scroll hook.
 */
public final class ClientTestContext
{
    private static final int DEFAULT_TIMEOUT_SECONDS = 5;

    private final Minecraft mc = Minecraft.getInstance();
    private final AtomicLong clientTicks;

    ClientTestContext(AtomicLong clientTicks) {
        this.clientTicks = clientTicks;
    }

    public Minecraft mc() {
        return mc;
    }

    // Threading

    /** Runs on the client thread and returns the result. */
    public <T> T onClient(Supplier<T> action) {
        try {
            return mc.submit(action).get(30, TimeUnit.SECONDS);
        } catch(Exception e) {
            throw unwrap("Client action failed", e);
        }
    }

    public void onClient(Runnable action) {
        onClient(() -> {
            action.run();
            return null;
        });
    }

    /** Runs on the integrated server thread with the single player's ServerPlayer and returns the result. */
    public <T> T onServer(Function<ServerPlayer, T> action) {
        var server = mc.getSingleplayerServer();
        if(server == null) throw new IllegalStateException("No integrated server");
        try {
            return server.submit(() -> action.apply(server.getPlayerList().getPlayers().get(0))).get(30, TimeUnit.SECONDS);
        } catch(Exception e) {
            throw unwrap("Server action failed", e);
        }
    }

    public void onServerDo(Consumer<ServerPlayer> action) {
        onServer(player -> {
            action.accept(player);
            return null;
        });
    }

    // Waiting and assertions

    public void waitTicks(int ticks) {
        long target = clientTicks.get() + ticks;
        await(ticks + " client ticks", 60, () -> clientTicks.get() >= target);
    }

    /** Polls a condition on the client thread until it holds; fails after the timeout. */
    public void await(String description, int timeoutSeconds, BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while(System.nanoTime() < deadline) {
            if(onClient(condition::getAsBoolean)) return;
            try {
                Thread.sleep(50);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Timed out after " + timeoutSeconds + "s waiting for: " + description);
    }

    public void await(String description, BooleanSupplier condition) {
        await(description, DEFAULT_TIMEOUT_SECONDS, condition);
    }

    public <S extends Screen> S awaitScreen(Class<S> type) {
        await(type.getSimpleName() + " to open", () -> type.isInstance(mc.screen));
        return onClient(() -> type.cast(mc.screen));
    }

    public static void check(boolean condition, String message) {
        if(!condition) throw new AssertionError(message);
    }

    // Input

    /** Holds or releases sneak through the sneak key mapping and waits until the player's crouch state matches. */
    public void sneak(boolean down) {
        onClient(() -> mc.options.keyShift.setDown(down));
        await("player crouch state " + down, () -> mc.player.isCrouching() == down);
        waitTicks(3);
    }

    /**
     * Presses or releases a key through the real keyboard handler, which fires InputEvent.Key and updates key
     * mappings. Also records it in {@link SimulatedKeys} for mods that poll the physical key state.
     */
    public void pressKey(int glfwKey, boolean down) {
        onClient(() -> {
            SimulatedKeys.set(glfwKey, down);
            mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), glfwKey, 0, down ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
        });
        waitTicks(3);
    }

    /** Queues one click of a key mapping, as if its key were pressed and released. */
    public void click(KeyMapping mapping) {
        onClient(() -> KeyMapping.click(mapping.getKey()));
        waitTicks(2);
    }

    public void clickUse() {
        click(mc.options.keyUse);
    }

    public void clickAttack() {
        click(mc.options.keyAttack);
    }

    /** Fires the mouse scroll event through NeoForge's hook. Negative values scroll down. */
    public void scroll(double delta) {
        onClient(() -> ClientHooks.onMouseScroll(mc.mouseHandler, 0, delta));
        waitTicks(2);
    }

    /** Sets the client player's view direction (yaw: 0 south, 180 north; pitch: -90 up, 90 down). */
    public void look(float yRot, float xRot) {
        onClient(() -> {
            mc.player.setYRot(yRot);
            mc.player.setXRot(xRot);
        });
        waitTicks(5);
    }

    /**
     * Runs {@code step} at the start of {@code ticks} consecutive client ticks (step index 0, 1, ...), before
     * Minecraft reads input for that tick, and waits until all steps ran. Use it for input that depends on exact
     * timing, such as a double tap: {@code perTick(4, i -> jumpKey.setDown(i % 2 == 0))}.
     */
    public void perTick(int ticks, IntConsumer step) {
        TickScript script = new TickScript(step, ticks);
        if(!ACTIVE_SCRIPT.compareAndSet(null, script)) throw new IllegalStateException("Another perTick script is running");
        try {
            script.done.get(Math.max(30, ticks / 10 + 10), TimeUnit.SECONDS);
        } catch(Exception e) {
            throw unwrap("perTick script failed", e);
        } finally {
            ACTIVE_SCRIPT.compareAndSet(script, null);
        }
    }

    private static final AtomicReference<TickScript> ACTIVE_SCRIPT = new AtomicReference<>();

    private static final class TickScript
    {
        final IntConsumer step;
        final int length;
        final CompletableFuture<Void> done = new CompletableFuture<>();
        int next;

        TickScript(IntConsumer step, int length) {
            this.step = step;
            this.length = length;
        }
    }

    /** Called by the runner at ClientTickEvent.Pre on the client thread. */
    static void onClientTickPre() {
        TickScript script = ACTIVE_SCRIPT.get();
        if(script == null || script.done.isDone()) return;
        try {
            if(script.next < script.length) script.step.accept(script.next++);
            if(script.next >= script.length) script.done.complete(null);
        } catch(Throwable t) {
            script.done.completeExceptionally(t);
        }
    }

    /** Releases every simulated input. The runner calls this after each test. */
    public void releaseAllInput() {
        onClient(() -> {
            mc.options.keyShift.setDown(false);
            for(int key : SimulatedKeys.held()) {
                SimulatedKeys.set(key, false);
                mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key, 0, GLFW.GLFW_RELEASE, 0);
            }
            if(mc.screen instanceof AbstractContainerScreen<?>) mc.player.closeContainer();
            else if(mc.screen != null) mc.setScreen(null);
        });
    }

    // World and inventory (server side)

    /** Switches the player's game mode and waits until the client sees it. The runner resets to creative after each test. */
    public void setGameMode(GameType mode) {
        onServerDo(p -> p.setGameMode(mode));
        await("client game mode " + mode, () -> mc.gameMode != null && mc.gameMode.getPlayerMode() == mode);
        waitTicks(2);
    }

    /**
     * Clears the inventory and puts the stack in hotbar slot 0. Waits for the client to see an empty hand first,
     * so a previous test's identical item cannot be mistaken for the new one.
     */
    public void giveMainHand(Supplier<ItemStack> stack) {
        onServerDo(p -> {
            p.getInventory().clearContent();
            p.getInventory().selected = 0;
            p.inventoryMenu.broadcastChanges();
        });
        onClient(() -> mc.player.getInventory().selected = 0);
        await("client inventory to clear", () -> mc.player.getMainHandItem().isEmpty());

        ItemStack given = onServer(p -> {
            ItemStack copy = stack.get();
            p.getInventory().setItem(0, copy);
            p.inventoryMenu.broadcastChanges();
            return copy.copy();
        });
        await("client to receive " + given, () -> ItemStack.isSameItemSameComponents(mc.player.getMainHandItem(), given));
    }

    /** Teleports the player to stand on the given block position (bottom center) with the given rotation. */
    public void teleport(BlockPos standing, float yRot, float xRot) {
        onServerDo(p -> p.teleportTo(p.serverLevel(), standing.getX() + 0.5, standing.getY(), standing.getZ() + 0.5, yRot, xRot));
        waitTicks(10);
    }

    /** Sets every block in the box (inclusive) on the server. */
    public void fill(BlockPos from, BlockPos to, BlockState state) {
        onServerDo(p -> BlockPos.betweenClosed(from, to).forEach(pos -> p.serverLevel().setBlockAndUpdate(pos, state)));
    }

    public BlockState serverBlock(BlockPos pos) {
        return onServer(p -> p.serverLevel().getBlockState(pos));
    }

    // Recipes and screenshots

    /**
     * Opens a real crafting table screen showing a crafting recipe: its ingredients in the grid (tag ingredients
     * use their first vanilla item) and the result the game computes in the output slot. Fails if the game does
     * not produce the recipe's result. Returns the result. Close the screen with {@link #closeContainer()}.
     */
    public ItemStack showCraftingRecipe(ResourceLocation recipeId) {
        Screenshots.RecipeGrid grid = onServer(p -> Screenshots.RecipeGrid.of(p, recipeId));
        showCrafting(grid.slots(), grid.result());
        return grid.result();
    }

    /**
     * Opens a crafting table screen with nine grid stacks (row by row, {@code ItemStack.EMPTY} for gaps) and waits
     * until the client shows the expected result in the output slot. Clears the player's inventory first and
     * places the table under the player.
     */
    public void showCrafting(List<ItemStack> grid, ItemStack expectedResult) {
        if(grid.size() != 9) throw new IllegalArgumentException("A crafting grid has 9 slots, got " + grid.size());
        onServerDo(p -> {
            // An empty inventory keeps leftovers from earlier tests out of the screenshot.
            p.getInventory().clearContent();
            BlockPos table = p.blockPosition().below(2);
            p.serverLevel().setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
            p.openMenu(new SimpleMenuProvider((id, inventory, player) -> new CraftingMenu(id, inventory,
                    ContainerLevelAccess.create(p.serverLevel(), table)), Component.translatable("container.crafting")));
            for(int i = 0; i < 9; i++) p.containerMenu.getSlot(i + 1).set(grid.get(i).copy());
            p.containerMenu.broadcastChanges();
        });
        awaitScreen(CraftingScreen.class);
        await("crafting output " + expectedResult, () -> mc.player.containerMenu instanceof CraftingMenu menu
                && ItemStack.isSameItemSameComponents(menu.getSlot(0).getItem(), expectedResult)
                && menu.getSlot(0).getItem().getCount() == expectedResult.getCount());
        waitTicks(5);
    }

    /** Closes the open container screen the way a player does, so the server closes the menu too. */
    public void closeContainer() {
        onClient(() -> mc.player.closeContainer());
        await("server to close the menu", () -> onServer(p -> p.containerMenu == p.inventoryMenu));
    }

    /**
     * Saves the open container screen (crafting table, furnace, your mod's GUI) as a PNG cropped to the GUI, at
     * GUI scale 2. Files go to {@code build/screenshots/<name>.png}. Returns the file.
     */
    public Path screenshotContainer(String name) {
        return screenshotContainer(name, 2);
    }

    /** As {@link #screenshotContainer(String)}, at a GUI scale clamped to what the window allows. */
    public Path screenshotContainer(String name, int guiScale) {
        return withGuiScale(guiScale, () -> onClient(() -> {
            if(!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
                throw new AssertionError("No container screen open, screen is " + mc.screen);
            }
            double scale = mc.getWindow().getGuiScale();
            return Screenshots.save(mc, name, (int) Math.round(screen.getGuiLeft() * scale), (int) Math.round(screen.getGuiTop() * scale),
                    (int) Math.round(screen.getXSize() * scale), (int) Math.round(screen.getYSize() * scale));
        }));
    }

    /**
     * Saves just the crafting grid, arrow and output slot of the open crafting table screen as a wiki style panel
     * (see {@link #screenshotPanel}), at 4 image pixels per GUI pixel. Returns the file.
     */
    public Path screenshotCraftingGrid(String name) {
        await("crafting table screen", () -> mc.screen instanceof CraftingScreen);
        // Grid slots span GUI x 29..83, y 16..70; the output slot frame spans x 119..145, y 30..56.
        return screenshotPanel(name, 29, 16, 117, 55);
    }

    /**
     * Saves a region of the open container GUI as a wiki style panel: the region (GUI pixels, relative to the GUI's
     * top left corner) with a small margin, everything outside the region repainted in the GUI background color,
     * and a beveled vanilla panel border. Captured at GUI scale 2 and upscaled to 4 image pixels per GUI pixel.
     */
    public Path screenshotPanel(String name, int x, int y, int width, int height) {
        return withGuiScale(2, () -> onClient(() -> {
            if(!(mc.screen instanceof AbstractContainerScreen<?> screen)) {
                throw new AssertionError("No container screen open, screen is " + mc.screen);
            }
            int px = (int) Math.round(mc.getWindow().getGuiScale());
            return Screenshots.savePanel(mc, name, (screen.getGuiLeft() + x) * px, (screen.getGuiTop() + y) * px,
                    width * px, height * px, 4 * px, px, Math.max(1, 4 / px));
        }));
    }

    /** Saves the whole window as a PNG to {@code build/screenshots/<name>.png}. Returns the file. */
    public Path screenshot(String name) {
        onClient(() -> {
            Screenshots.parkMouse(mc);
            mc.getToasts().clear();
        });
        waitTicks(3);
        return onClient(() -> Screenshots.save(mc, name, 0, 0, mc.getWindow().getWidth(), mc.getWindow().getHeight()));
    }

    // Applies a GUI scale, lets a few frames render, runs the capture, then restores the player's setting.
    private <T> T withGuiScale(int guiScale, Supplier<T> capture) {
        int previous = onClient(() -> mc.options.guiScale().get());
        try {
            onClient(() -> {
                mc.options.guiScale().set(mc.getWindow().calculateScale(guiScale, mc.isEnforceUnicode()));
                mc.resizeDisplay();
                Screenshots.parkMouse(mc);
                mc.getToasts().clear();
            });
            waitTicks(5);
            return capture.get();
        } finally {
            onClient(() -> {
                mc.options.guiScale().set(previous);
                mc.resizeDisplay();
            });
        }
    }

    // Rethrows assertion failures from the client or server thread as-is, so the report shows the real message.
    private static RuntimeException unwrap(String context, Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        if(cause instanceof AssertionError assertion) throw assertion;
        if(cause instanceof RuntimeException runtime) return runtime;
        return new RuntimeException(context, cause);
    }
}
