package io.github.peterbax117.modtestharness.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;

/**
 * GameTest fixtures. Positions are relative to the test structure unless noted, matching GameTestHelper.
 * Test bodies run within one server tick, so a temporary config change made and restored inside one test
 * cannot leak into other tests.
 */
public final class GameTestSupport
{
    private GameTestSupport() {
    }

    /**
     * A survival FakePlayer with a unique profile (so per-player state such as undo history does not leak
     * between tests), standing on the given relative position with the given rotation and main-hand item.
     * FakePlayers are not added to the level, so they never collide with placed blocks and ignore packets.
     */
    public static FakePlayer survivalPlayer(GameTestHelper helper, ItemStack mainHand, BlockPos standing, float yRot, float xRot) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "harness-test"));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.fallDistance = 0;
        Vec3 pos = Vec3.atBottomCenterOf(helper.absolutePos(standing));
        player.moveTo(pos.x, pos.y, pos.z, yRot, xRot);
        player.setItemInHand(InteractionHand.MAIN_HAND, mainHand);
        return player;
    }

    /** Right-clicks a block face with the main-hand item, like Minecraft does for a player. */
    public static InteractionResult useItemOn(GameTestHelper helper, Player player, BlockPos relative, Direction face) {
        BlockPos pos = helper.absolutePos(relative);
        Vec3 hitLocation = Vec3.atCenterOf(pos).relative(face, 0.5);
        UseOnContext context = new UseOnContext(player, InteractionHand.MAIN_HAND, new BlockHitResult(hitLocation, face, pos, false));
        return player.getMainHandItem().useOn(context);
    }

    /** Right-clicks into the air with the main-hand item. */
    public static void useItemInAir(GameTestHelper helper, Player player) {
        player.getMainHandItem().getItem().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
    }

    /** Sets a horizontal rectangle of blocks at height y (inclusive bounds). */
    public static void fill(GameTestHelper helper, BlockState state, int x1, int x2, int y, int z1, int z2) {
        for(int x = x1; x <= x2; x++) for(int z = z1; z <= z2; z++) helper.setBlock(new BlockPos(x, y, z), state);
    }

    public static void fill(GameTestHelper helper, Block block, int x1, int x2, int y, int z1, int z2) {
        fill(helper, block.defaultBlockState(), x1, x2, y, z1, z2);
    }

    /** Counts blocks of a type in the whole structure. forEveryBlockInStructure yields relative positions. */
    public static int countInStructure(GameTestHelper helper, Block block) {
        int[] count = {0};
        helper.forEveryBlockInStructure(pos -> {
            if(helper.getBlockState(pos).is(block)) count[0]++;
        });
        return count[0];
    }

    /** Counts blocks of a type in one horizontal layer (relative y) of the structure. */
    public static int countInLayer(GameTestHelper helper, Block block, int y) {
        int[] count = {0};
        helper.forEveryBlockInStructure(pos -> {
            if(pos.getY() == y && helper.getBlockState(pos).is(block)) count[0]++;
        });
        return count[0];
    }

    /** Asserts every block in the box (inclusive, relative) is of the given type. */
    public static void assertBox(GameTestHelper helper, Block block, BlockPos from, BlockPos to) {
        BlockPos.betweenClosed(from, to).forEach(pos -> helper.assertBlockPresent(block, pos.immutable()));
    }

    /** Counts an item across the main inventory and off hand. */
    public static int countItem(Player player, Item item) {
        int total = 0;
        for(ItemStack stack : player.getInventory().items) if(stack.is(item)) total += stack.getCount();
        for(ItemStack stack : player.getInventory().offhand) if(stack.is(item)) total += stack.getCount();
        return total;
    }

    /** Puts {@code count} of an item into inventory slots 1 and up, in full stacks. */
    public static void giveStacks(Player player, Item item, int count) {
        int slot = 1;
        while(count > 0) {
            int size = Math.min(item.getDefaultMaxStackSize(), count);
            player.getInventory().setItem(slot++, new ItemStack(item, size));
            count -= size;
        }
    }
}
