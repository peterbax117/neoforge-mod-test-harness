package io.github.peterbax117.modtestharness.selftest;

import io.github.peterbax117.modtestharness.ModTestHarness;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.peterbax117.modtestharness.gametest.GameTestSupport.*;
import static io.github.peterbax117.modtestharness.gametest.HarnessTemplates.EMPTY_15X8X15;
import static io.github.peterbax117.modtestharness.gametest.HarnessTemplates.EMPTY_9X5X9;

/** Self-tests for the GameTest fixtures, using vanilla content only. */
@GameTestHolder(ModTestHarness.MODID)
@PrefixGameTestTemplate(false)
public class HarnessGameTests
{
    @GameTest(template = EMPTY_15X8X15)
    public static void templatesAreEmpty(GameTestHelper helper) {
        helper.assertValueEqual(countInStructure(helper, Blocks.STONE), 0, "stone in the empty template");
        helper.succeed();
    }

    @GameTest(template = EMPTY_9X5X9)
    public static void fillAndCountAgree(GameTestHelper helper) {
        fill(helper, Blocks.STONE, 3, 5, 1, 3, 5);
        helper.assertValueEqual(countInLayer(helper, Blocks.STONE, 1), 9, "stone in layer 1");
        helper.assertValueEqual(countInLayer(helper, Blocks.STONE, 2), 0, "stone in layer 2");
        helper.assertValueEqual(countInStructure(helper, Blocks.STONE), 9, "stone in structure");
        assertBox(helper, Blocks.STONE, new BlockPos(3, 1, 3), new BlockPos(5, 1, 5));
        helper.succeed();
    }

    @GameTest(template = EMPTY_9X5X9)
    public static void survivalPlayerPlacesBlockItem(GameTestHelper helper) {
        fill(helper, Blocks.STONE, 3, 5, 1, 3, 5);
        FakePlayer player = survivalPlayer(helper, new ItemStack(Items.COBBLESTONE, 2), new BlockPos(4, 2, 0), 0, 0);

        useItemOn(helper, player, new BlockPos(4, 1, 4), Direction.UP);

        helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(4, 2, 4));
        helper.assertValueEqual(countItem(player, Items.COBBLESTONE), 1, "cobblestone left (survival consumes items)");
        helper.succeed();
    }

    @GameTest(template = EMPTY_9X5X9)
    public static void giveStacksSplitsIntoFullStacks(GameTestHelper helper) {
        FakePlayer player = survivalPlayer(helper, ItemStack.EMPTY, new BlockPos(4, 2, 0), 0, 0);
        giveStacks(player, Items.STONE, 150);
        helper.assertValueEqual(countItem(player, Items.STONE), 150, "stone given");
        helper.assertValueEqual(player.getInventory().getItem(3).getCount(), 22, "last stack size");
        helper.succeed();
    }
}
