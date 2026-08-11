package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.client.BridgeConfig;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

final class NpcInteractionProxy {
    private final CodexNpcEntity npc;
    private final BridgeConfig config;

    NpcInteractionProxy(CodexNpcEntity npc, BridgeConfig config) {
        this.npc = npc;
        this.config = config;
    }

    boolean breakBlock(BlockPos position, int toolSlot) {
        FakePlayer player = prepare(toolSlot >= 0 ? npc.inventory().getStackInSlot(toolSlot) : ItemStack.EMPTY);
        aimAt(player, Vec3.atCenterOf(position));
        boolean broken = player.gameMode.destroyBlock(position);
        if (toolSlot >= 0) npc.inventory().setStackInSlot(toolSlot, player.getMainHandItem().copy());
        return broken;
    }

    InteractionResult useItemOn(BlockPos support, Direction face, ItemStack stack, int sourceSlot) {
        FakePlayer player = prepare(stack.copy());
        Vec3 hit = supportHitLocation(player.level(), support, face);
        InteractionResult result = player.gameMode.useItemOn(
            player,
            player.level(),
            player.getMainHandItem(),
            InteractionHand.MAIN_HAND,
            new BlockHitResult(hit, face, support, false)
        );
        if (sourceSlot >= 0) npc.inventory().setStackInSlot(sourceSlot, player.getMainHandItem().copy());
        return result;
    }

    /**
     * Uses an item whose vanilla behavior performs its own view ray cast, such
     * as a filled bucket. This keeps Forge's normal right-click hooks active.
     */
    InteractionResult useItemToward(BlockPos support, Direction face, ItemStack stack, int sourceSlot) {
        FakePlayer player = prepare(stack.copy());
        Vec3 hit = supportHitLocation(player.level(), support, face);
        aimAt(player, hit);
        InteractionResult result = player.gameMode.useItem(
            player,
            player.level(),
            player.getMainHandItem(),
            InteractionHand.MAIN_HAND
        );
        if (sourceSlot >= 0) npc.inventory().setStackInSlot(sourceSlot, player.getMainHandItem().copy());
        return result;
    }

    InteractionResult interact(Entity entity, ItemStack stack, int sourceSlot) {
        FakePlayer player = prepare(stack.copy());
        aimAt(player, entity.getBoundingBox().getCenter());
        InteractionResult result = player.interactOn(entity, InteractionHand.MAIN_HAND);
        if (sourceSlot >= 0) npc.inventory().setStackInSlot(sourceSlot, player.getMainHandItem().copy());
        return result;
    }

    /** Performs the vanilla lead interaction, then transfers the holder from the proxy player to the visible NPC. */
    boolean leashToNpc(Mob mob, int sourceSlot) {
        if (sourceSlot < 0) return false;
        InteractionResult result = interact(mob, npc.inventory().getStackInSlot(sourceSlot), sourceSlot);
        if (!result.consumesAction() || !mob.isLeashed()) return false;
        mob.setLeashedTo(npc, true);
        return mob.getLeashHolder() == npc;
    }

    /** Casts a real vanilla fishing hook owned by the private FakePlayer proxy. */
    boolean castFishing(int sourceSlot) {
        if (sourceSlot < 0) return false;
        FakePlayer player = prepare(npc.inventory().getStackInSlot(sourceSlot).copy());
        if (player.fishing != null) {
            player.fishing.discard();
            player.fishing = null;
        }
        InteractionResultHolder<ItemStack> result = player.getMainHandItem().use(
            player.level(), player, InteractionHand.MAIN_HAND
        );
        npc.inventory().setStackInSlot(sourceSlot, result.getObject().copy());
        return result.getResult().consumesAction() && player.fishing != null;
    }

    /** Reels in and removes the visible hook while preserving rod durability. */
    void reelFishing(int sourceSlot) {
        if (sourceSlot < 0) return;
        FakePlayer player = prepare(npc.inventory().getStackInSlot(sourceSlot).copy());
        if (player.fishing == null) return;
        InteractionResultHolder<ItemStack> result = player.getMainHandItem().use(
            player.level(), player, InteractionHand.MAIN_HAND
        );
        npc.inventory().setStackInSlot(sourceSlot, result.getObject().copy());
    }

    void cancelFishing() {
        FakePlayer player = prepare(ItemStack.EMPTY);
        if (player.fishing == null) return;
        player.fishing.discard();
        player.fishing = null;
    }

    private FakePlayer prepare(ItemStack mainHand) {
        ServerLevel level = (ServerLevel) npc.level();
        ServerPlayer owner = npc.owner();
        // Protection mods commonly key Forge right-click events by the complete
        // profile. A mismatched owner UUID/NPC name pair is neither identity.
        GameProfile profile = owner == null
            ? new GameProfile(npc.getUUID(), config.name)
            : owner.getGameProfile();
        FakePlayer player = FakePlayerFactory.get(level, profile);
        player.setPos(npc.getX(), npc.getY(), npc.getZ());
        player.setYRot(npc.getYRot());
        player.setXRot(npc.getXRot());
        player.gameMode.changeGameModeForPlayer(npc.creativeResources() ? GameType.CREATIVE : GameType.SURVIVAL);
        player.getInventory().selected = 0;
        player.getInventory().setItem(0, mainHand);
        return player;
    }

    private static Vec3 supportHitLocation(Level level, BlockPos support, Direction face) {
        VoxelShape shape = level.getBlockState(support).getCollisionShape(level, support);
        AABB bounds = shape.isEmpty() ? new AABB(0, 0, 0, 1, 1, 1) : shape.bounds();
        double x = (bounds.minX + bounds.maxX) * 0.5;
        double y = (bounds.minY + bounds.maxY) * 0.5;
        double z = (bounds.minZ + bounds.maxZ) * 0.5;
        if (face.getAxis() == Direction.Axis.X) x = face == Direction.EAST ? bounds.maxX : bounds.minX;
        if (face.getAxis() == Direction.Axis.Y) y = face == Direction.UP ? bounds.maxY : bounds.minY;
        if (face.getAxis() == Direction.Axis.Z) z = face == Direction.SOUTH ? bounds.maxZ : bounds.minZ;
        return new Vec3(support.getX() + x, support.getY() + y, support.getZ() + z);
    }

    private static void aimAt(FakePlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        double x = target.x - eye.x;
        double y = target.y - eye.y;
        double z = target.z - eye.z;
        double horizontal = Math.sqrt(x * x + z * z);
        float yaw = (float) (Math.toDegrees(Math.atan2(z, x)) - 90.0D);
        float pitch = (float) -Math.toDegrees(Math.atan2(y, horizontal));
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.yBodyRot = yaw;
    }
}
