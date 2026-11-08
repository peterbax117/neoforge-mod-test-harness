package io.github.peterbax117.modtestharness.selftest;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.peterbax117.modtestharness.client.ClientTest;
import io.github.peterbax117.modtestharness.client.ClientTestContext;
import io.github.peterbax117.modtestharness.client.ClientTestHolder;
import io.github.peterbax117.modtestharness.client.SimulatedKeys;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.peterbax117.modtestharness.client.ClientTestContext.check;

/** Self-tests for the client runner and input helpers, using vanilla content only. */
@ClientTestHolder
public class HarnessClientTests
{
    private static final BlockPos STAND = new BlockPos(0, -60, 0);
    private static volatile double lastScroll;

    @EventBusSubscriber(modid = "modtestharness", value = Dist.CLIENT)
    public static final class ScrollRecorder
    {
        @SubscribeEvent
        public static void onScroll(InputEvent.MouseScrollingEvent event) {
            lastScroll = event.getScrollDeltaY();
        }
    }

    @ClientTest(value = "inventory key opens the creative inventory", order = 1)
    public static void inventoryKey(ClientTestContext ctx) {
        ctx.pressKey(GLFW.GLFW_KEY_E, true);
        ctx.pressKey(GLFW.GLFW_KEY_E, false);
        ctx.awaitScreen(CreativeModeInventoryScreen.class);
    }

    @ClientTest(value = "pressed keys update key mappings and SimulatedKeys", order = 2)
    public static void keyState(ClientTestContext ctx) {
        ctx.pressKey(GLFW.GLFW_KEY_LEFT_CONTROL, true);
        check(SimulatedKeys.isDown(GLFW.GLFW_KEY_LEFT_CONTROL), "SimulatedKeys should report left control down");
        check(ctx.onClient(() -> ctx.mc().options.keySprint.isDown()), "Sprint key mapping should be down");
        ctx.pressKey(GLFW.GLFW_KEY_LEFT_CONTROL, false);
        check(!SimulatedKeys.isDown(GLFW.GLFW_KEY_LEFT_CONTROL), "SimulatedKeys should report left control up");
    }

    @ClientTest(value = "sneak reaches the server", order = 3)
    public static void sneakReachesServer(ClientTestContext ctx) {
        ctx.sneak(true);
        ctx.await("server player to crouch", () -> ctx.onServer(p -> p.isCrouching()));
        ctx.sneak(false);
        ctx.await("server player to stand", () -> ctx.onServer(p -> !p.isCrouching()));
    }

    @ClientTest(value = "use key places the held block on the targeted face", order = 4)
    public static void useKeyPlacesBlock(ClientTestContext ctx) {
        ctx.fill(new BlockPos(-2, -60, -4), new BlockPos(2, -57, 2), Blocks.AIR.defaultBlockState());
        BlockPos target = new BlockPos(0, -59, -2);
        ctx.fill(target, target, Blocks.STONE.defaultBlockState());
        ctx.giveMainHand(() -> new ItemStack(Items.COBBLESTONE, 4));
        ctx.teleport(STAND, 180, 0);
        ctx.look(180, 0);

        ctx.clickUse();

        ctx.await("cobblestone placed in front of the target", () -> ctx.serverBlock(target.south()).is(Blocks.COBBLESTONE));
    }

    @ClientTest(value = "scroll fires the mouse scrolling event", order = 5)
    public static void scrollEvent(ClientTestContext ctx) {
        lastScroll = 0;
        ctx.scroll(-1);
        check(lastScroll == -1, "Scroll event should report -1, got " + lastScroll);
    }

    @ClientTest(value = "giveMainHand replaces an identical item reliably", order = 6)
    public static void giveMainHandTwice(ClientTestContext ctx) {
        ctx.giveMainHand(() -> new ItemStack(Items.STONE, 5));
        ctx.giveMainHand(() -> new ItemStack(Items.STONE, 7));
        check(ctx.onClient(() -> ctx.mc().player.getMainHandItem().getCount()) == 7, "Client should hold the second stack");
    }

    @ClientTest(value = "setGameMode switches mode on both sides", order = 7)
    public static void gameMode(ClientTestContext ctx) {
        ctx.setGameMode(GameType.SURVIVAL);
        check(ctx.onServer(p -> p.gameMode.getGameModeForPlayer()) == GameType.SURVIVAL, "Server should be in survival");
        check(!ctx.onClient(() -> ctx.mc().player.getAbilities().mayfly), "Survival player should not be able to fly");
    }

    @ClientTest(value = "runner resets the game mode to creative after a test", order = 8)
    public static void gameModeReset(ClientTestContext ctx) {
        check(ctx.onServer(p -> p.gameMode.getGameModeForPlayer()) == GameType.CREATIVE, "Previous test's survival mode should be reset");
    }

    @ClientTest(value = "perTick runs one step per consecutive client tick", order = 9)
    public static void perTickIsConsecutive(ClientTestContext ctx) {
        long[] gameTimes = new long[5];
        ctx.perTick(5, i -> gameTimes[i] = ctx.mc().level.getGameTime());
        for(int i = 1; i < gameTimes.length; i++) {
            check(gameTimes[i] - gameTimes[i - 1] == 1, "Steps should run on consecutive ticks: " + java.util.Arrays.toString(gameTimes));
        }
    }

    @ClientTest(value = "showCraftingRecipe and screenshotContainer save the crafting GUI", order = 10)
    public static void recipeScreenshot(ClientTestContext ctx) throws java.io.IOException {
        ItemStack result = ctx.showCraftingRecipe(ResourceLocation.withDefaultNamespace("crafting_table"));
        check(result.is(Items.CRAFTING_TABLE), "Recipe result should be a crafting table, got " + result);

        Path file = ctx.screenshotContainer("selftest-crafting_table");
        try(var in = Files.newInputStream(file); var image = NativeImage.read(in)) {
            // The crafting GUI is 176x166 GUI pixels; the default window allows GUI scale 2.
            check(image.getWidth() == 352 && image.getHeight() == 332, "Unexpected screenshot size " + image.getWidth() + "x" + image.getHeight());
        }
        Path grid = ctx.screenshotCraftingGrid("selftest-crafting_table-grid");
        try(var in = Files.newInputStream(grid); var image = NativeImage.read(in)) {
            // 117x55 GUI pixels of grid, arrow and output, plus a 4 pixel margin, at 8 image pixels per GUI pixel.
            check(image.getWidth() == 1000 && image.getHeight() == 504, "Unexpected grid panel size " + image.getWidth() + "x" + image.getHeight());
        }
        ctx.closeContainer();
        check(ctx.onServer(p -> p.getInventory().items.stream().filter(s -> s.is(ItemTags.PLANKS)).mapToInt(ItemStack::getCount).sum()) == 4,
                "Closing should return the grid's 4 planks to the player");
    }
}
