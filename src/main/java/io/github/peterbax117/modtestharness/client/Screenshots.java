package io.github.peterbax117.modtestharness.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Recipe layout and PNG capture behind the ClientTestContext screenshot helpers. */
final class Screenshots
{
    static final String DIR_PROPERTY = "modtestharness.screenshots.dir";

    private Screenshots() {
    }

    /** A crafting recipe laid out on a 3x3 grid, row by row, plus its result. */
    record RecipeGrid(List<ItemStack> slots, ItemStack result) {
        static RecipeGrid of(ServerPlayer player, ResourceLocation recipeId) {
            var holder = player.server.getRecipeManager().byKey(recipeId)
                    .orElseThrow(() -> new AssertionError("No recipe " + recipeId));
            if(!(holder.value() instanceof CraftingRecipe recipe)) throw new AssertionError(recipeId + " is not a crafting recipe");

            // Shaped recipes keep their width; shapeless ones fill the grid left to right.
            int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 3;
            List<Ingredient> ingredients = recipe.getIngredients();
            if(ingredients.size() > 9 || width > 3) throw new AssertionError(recipeId + " does not fit a 3x3 grid");

            List<ItemStack> slots = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
            for(int i = 0; i < ingredients.size(); i++) {
                slots.set(i / width * 3 + i % width, displayStack(ingredients.get(i)));
            }
            return new RecipeGrid(slots, recipe.getResultItem(player.registryAccess()).copy());
        }

        /** Tag ingredients show their first vanilla item, so screenshots look the same with or without other mods. */
        private static ItemStack displayStack(Ingredient ingredient) {
            ItemStack[] options = ingredient.getItems();
            if(options.length == 0) return ItemStack.EMPTY;
            return Arrays.stream(options)
                    .filter(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace().equals("minecraft"))
                    .findFirst().orElse(options[0]).copyWithCount(1);
        }
    }

    /** Moves the GUI mouse position to the top left corner so no slot is hovered and no tooltip is drawn. */
    static void parkMouse(Minecraft mc) {
        try {
            for(String name : new String[] {"xpos", "ypos"}) {
                Field field = MouseHandler.class.getDeclaredField(name);
                field.setAccessible(true);
                field.setDouble(mc.mouseHandler, 0);
            }
        } catch(ReflectiveOperationException ignored) {
            // Only cosmetic: a hovered slot would add a tooltip to the image.
        }
    }

    /** Captures the last rendered frame, crops it to the given window pixel rectangle and writes {@code <name>.png}. */
    static Path save(Minecraft mc, String name, int x, int y, int width, int height) {
        if(!name.matches("[a-z0-9_.-]+")) throw new IllegalArgumentException("Screenshot name must match [a-z0-9_.-]+: " + name);
        Path file = directory(mc).resolve(name + ".png");
        try(NativeImage frame = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int left = Math.max(0, x);
            int top = Math.max(0, y);
            int w = Math.min(width, frame.getWidth() - left);
            int h = Math.min(height, frame.getHeight() - top);
            if(w <= 0 || h <= 0) throw new AssertionError("Crop " + x + "," + y + " " + width + "x" + height + " is outside the window");
            try(NativeImage out = new NativeImage(w, h, false)) {
                for(int j = 0; j < h; j++) {
                    for(int i = 0; i < w; i++) out.setPixelRGBA(i, j, frame.getPixelRGBA(left + i, top + j));
                }
                Files.createDirectories(file.getParent());
                out.writeToFile(file);
            }
        } catch(IOException e) {
            throw new UncheckedIOException("Could not write screenshot " + file, e);
        }
        return file;
    }

    private static Path directory(Minecraft mc) {
        String dir = System.getProperty(DIR_PROPERTY);
        return dir != null ? Path.of(dir) : mc.gameDirectory.toPath().resolve("screenshots").resolve("modtestharness");
    }
}
