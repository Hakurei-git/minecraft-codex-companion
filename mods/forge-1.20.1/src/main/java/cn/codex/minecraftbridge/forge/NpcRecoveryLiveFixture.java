package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Reversible live acceptance for lethal NPC recovery and owner recall. */
final class NpcRecoveryLiveFixture {
    private static final String STATE_KEY = "CodexAcceptanceNpcRecoveryState";
    private static final String SAVED_NPC_KEY = "SavedNpc";
    private static final int[][] STAGE_OFFSETS = {
        {16, 0}, {-16, 0}, {0, 16}, {0, -16},
        {12, 12}, {12, -12}, {-12, 12}, {-12, -12}
    };

    private NpcRecoveryLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("NPC recovery fixture requires an in-world NPC");
        switch (mode) {
            case "setup" -> setup(player, npc);
            case "down" -> down(player, npc);
            case "inspect" -> inspect(player, npc);
            case "cleanup" -> cleanup(player, npc);
            default -> throw new IllegalArgumentException("Unknown NPC recovery fixture mode");
        }
    }

    private static void setup(ServerPlayer player, CodexNpcEntity npc) {
        if (!player.isAlive() || player.isRemoved() || !npc.isAlive() || npc.isRemoved() || npc.isDowned()) {
            throw new IllegalStateException("NPC recovery fixture requires living active actors");
        }
        if (npc.level() != player.level()) {
            throw new IllegalStateException("NPC recovery fixture requires player and NPC in the same dimension");
        }
        if (!npc.tasks().activeTaskId().isBlank() || npc.tasks().pausedTaskCount() > 0) {
            throw new IllegalStateException("NPC recovery fixture requires an idle scheduler");
        }
        if (npc.getPersistentData().contains(STATE_KEY, Tag.TAG_COMPOUND)) {
            cleanup(player, npc);
        }

        CompoundTag state = new CompoundTag();
        state.put(SAVED_NPC_KEY, npc.saveWithoutId(new CompoundTag()));
        Vec3 staged = safeStagedPosition(player, npc);
        npc.getPersistentData().put(STATE_KEY, state);

        npc.tasks().stay();
        npc.setHealth(npc.getMaxHealth());
        npc.setFoodLevel(Math.max(10, npc.foodLevel()));
        npc.setNoGravity(false);
        npc.teleportTo(staged.x, staged.y, staged.z);
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
        int distanceMilli = (int) Math.round(npc.distanceTo(player) * 1_000.0D);
        npc.setNextLiveFixtureAckStatus("npc-recovery:setup distanceMilli=" + distanceMilli);
    }

    private static void down(ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag state = requiredState(player, npc);
        if (npc.isDowned()) throw new IllegalStateException("NPC recovery fixture is already downed");
        boolean accepted = npc.hurt(npc.damageSources().generic(), npc.getHealth());
        if (!accepted || !npc.isDowned()) {
            throw new IllegalStateException("NPC recovery fixture could not trigger the downed state");
        }
        state.putBoolean("ObservedDowned", true);
        npc.getPersistentData().put(STATE_KEY, state);
        npc.setNextLiveFixtureAckStatus("npc-recovery:down downed=1");
    }

    private static void inspect(ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag state = requiredState(player, npc);
        boolean observed = state.getBoolean("ObservedDowned");
        boolean recovered = observed && !npc.isDowned();
        int distanceMilli = (int) Math.round(npc.distanceTo(player) * 1_000.0D);
        boolean returned = recovered && distanceMilli <= 8_000;
        npc.setNextLiveFixtureAckStatus(
            "npc-recovery:inspect down=" + bit(npc.isDowned())
                + ",observed=" + bit(observed)
                + ",recovered=" + bit(recovered)
                + ",returned=" + bit(returned)
                + ",distanceMilli=" + distanceMilli
        );
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc) {
        if (!npc.getPersistentData().contains(STATE_KEY, Tag.TAG_COMPOUND)) {
            npc.setNextLiveFixtureAckStatus("npc-recovery:cleanup none");
            return;
        }
        CompoundTag state = npc.getPersistentData().getCompound(STATE_KEY).copy();
        if (!state.contains(SAVED_NPC_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("NPC recovery fixture baseline is missing");
        }
        npc.load(state.getCompound(SAVED_NPC_KEY).copy());
        npc.getNavigation().stop();
        npc.setDeltaMovement(Vec3.ZERO);
        npc.fallDistance = 0.0F;
        npc.setNextLiveFixtureAckStatus("npc-recovery:cleanup restored=1");
    }

    private static CompoundTag requiredState(ServerPlayer player, CodexNpcEntity npc) {
        if (npc.level() != player.level()) {
            throw new IllegalStateException("NPC recovery fixture dimension changed");
        }
        if (!npc.getPersistentData().contains(STATE_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("NPC recovery fixture has not been set up");
        }
        return npc.getPersistentData().getCompound(STATE_KEY).copy();
    }

    private static Vec3 safeStagedPosition(ServerPlayer player, CodexNpcEntity npc) {
        for (int[] offset : STAGE_OFFSETS) {
            BlockPos anchor = player.blockPosition().offset(offset[0], 0, offset[1]);
            Vec3 candidate = NpcManager.recallPosition(NpcManager.safePosition(player.serverLevel(), anchor));
            double distance = candidate.distanceTo(player.position());
            if (distance < 10.0D || distance > 24.0D) continue;
            AABB moved = npc.getBoundingBox().move(candidate.subtract(npc.position()));
            if (player.serverLevel().noCollision(npc, moved)) return candidate;
        }
        throw new IllegalStateException("No safe NPC recovery staging position was found");
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
