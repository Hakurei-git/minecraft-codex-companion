package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import cn.codex.minecraftbridge.client.BridgeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class NpcManager {
    private static final String NPC_RECORD = "MinecraftCodexNpcRecord";
    private static final String RECORD_UUID = "Uuid";
    private static final String RECORD_BACKUP = "Backup";
    private static final String RECORD_DIMENSION = "Dimension";
    private static final String RECORD_VERSION = "Version";
    private static final String NPC_UUID = "MinecraftCodexNpcUuid";
    private static final String NPC_BACKUP = "MinecraftCodexNpcBackup";
    private static final String NPC_DIMENSION = "MinecraftCodexNpcDimension";
    private static final BridgeConfig CONFIG = BridgeConfig.load();

    private NpcManager() {
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldOwnNpc(player) && CONFIG.npcAutoSpawn) ensure(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldOwnNpc(player)) ensure(player);
    }

    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        CompoundTag original = event.getOriginal().getPersistentData();
        CompoundTag replacement = event.getEntity().getPersistentData();
        if (original.contains(NPC_RECORD, Tag.TAG_COMPOUND)) {
            replacement.put(NPC_RECORD, original.getCompound(NPC_RECORD).copy());
            return;
        }
        if (original.hasUUID(NPC_UUID)) replacement.putUUID(NPC_UUID, original.getUUID(NPC_UUID));
        if (original.contains(NPC_BACKUP, Tag.TAG_COMPOUND)) {
            replacement.put(NPC_BACKUP, original.getCompound(NPC_BACKUP).copy());
        }
        if (original.contains(NPC_DIMENSION, Tag.TAG_STRING)) {
            replacement.putString(NPC_DIMENSION, original.getString(NPC_DIMENSION));
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof CodexNpcEntity npc)) return;
        ServerPlayer owner = npc.owner();
        if (owner != null && !isCanonical(owner, npc)) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && shouldOwnNpc(player)) {
            CodexNpcEntity npc = find(player);
            if (npc != null) recall(player, npc);
            else if (CONFIG.npcAutoSpawn) ensure(player);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CodexNpcEntity npc = find(player);
            if (npc != null) {
                npc.tasks().onOwnerOffline();
                remember(player, npc);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END
            || !(event.player instanceof ServerPlayer player)
            || !shouldOwnNpc(player)
            || player.tickCount % 20 != 0) return;
        UUID canonicalUuid = remembered(player);
        if (canonicalUuid == null) return;
        CodexNpcEntity canonical = findLoadedByUuid(player, canonicalUuid);
        if (canonical != null) discardOwnedDuplicates(player, canonical);
    }

    @SubscribeEvent
    public static void onPlayerAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer owner && event.getTarget() instanceof LivingEntity target) {
            requestCombatAssist(owner, target);
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer owner) {
            requestCombatAssist(owner, event.getEntity());
        }
        if (event.getEntity() instanceof ServerPlayer owner
            && event.getSource().getEntity() instanceof LivingEntity attacker) {
            requestCombatAssist(owner, attacker);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide || event.getEntity().level().getServer() == null) return;
        UUID targetId = event.getEntity().getUUID();
        for (ServerLevel level : event.getEntity().level().getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CodexNpcEntity npc) npc.tasks().onLivingEntityDefeated(targetId);
            }
        }
    }

    public static boolean shouldOwnNpc(ServerPlayer player) {
        return CONFIG.ownerName.isBlank() || player.getGameProfile().getName().equalsIgnoreCase(CONFIG.ownerName);
    }

    public static boolean isCanonical(ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag record = npcRecord(player);
        return !record.hasUUID(RECORD_UUID)
            || !record.contains(RECORD_BACKUP, Tag.TAG_COMPOUND)
            || record.getUUID(RECORD_UUID).equals(npc.getUUID());
    }

    private static void requestCombatAssist(ServerPlayer owner, LivingEntity target) {
        if (!shouldOwnNpc(owner)) return;
        CodexNpcEntity npc = find(owner);
        if (npc != null) npc.tasks().assistOwnerAgainst(owner, target);
    }

    public static CodexNpcEntity ensure(ServerPlayer player) {
        CodexNpcEntity existing = find(player);
        if (existing == null && hasRememberedBackup(player)) {
            existing = restoreRemembered(player);
            if (existing == null) return null;
        }
        if (existing != null) {
            applyConfiguredName(existing);
            existing.tasks().onOwnerOnline();
            remember(player, existing);
            discardOwnedDuplicates(player, existing);
            CodexNetwork.sendSnapshot(player, existing);
            return existing;
        }
        CodexNpcEntity created = ModEntities.CODEX_NPC.get().create(player.serverLevel(), null, null, player.blockPosition(), MobSpawnType.COMMAND, false, false);
        if (created == null) throw new IllegalStateException("Unable to create Codex NPC");
        created.setOwner(player);
        applyConfiguredName(created);
        created.moveTo(safePosition(player.serverLevel(), player.blockPosition()), player.getYRot(), 0.0F);
        created.setStance(NpcTaskEngine.Stance.FOLLOW);
        created.setStatus("已来到 " + player.getGameProfile().getName() + " 身边");
        if (!player.serverLevel().addFreshEntity(created)) {
            Entity conflict = player.serverLevel().getEntity(created.getUUID());
            if (conflict instanceof CodexNpcEntity npc && npc.isOwnedBy(player)) return npc;
            throw new IllegalStateException("Unable to add Codex NPC to the world");
        }
        remember(player, created);
        discardOwnedDuplicates(player, created);
        CodexNetwork.sendSpeech(player, "我来了，" + player.getGameProfile().getName() + "。");
        CodexNetwork.sendSnapshot(player, created);
        return created;
    }

    public static CodexNpcEntity find(ServerPlayer player) {
        UUID remembered = remembered(player);
        if (remembered != null) {
            for (ServerLevel level : player.server.getAllLevels()) {
                Entity entity = level.getEntity(remembered);
                if (entity instanceof CodexNpcEntity npc && npc.isOwnedBy(player)) return npc;
            }
            if (hasRememberedBackup(player)) return null;
        }
        for (ServerLevel level : player.server.getAllLevels()) {
            CodexNpcEntity nearby = level.getEntitiesOfClass(
                CodexNpcEntity.class,
                player.getBoundingBox().inflate(256),
                npc -> npc.isOwnedBy(player)
            ).stream().findFirst().orElse(null);
            if (nearby != null) {
                remember(player, nearby);
                return nearby;
            }
        }
        return null;
    }

    public static CodexNpcEntity recall(ServerPlayer player, CodexNpcEntity npc) {
        BlockPos destination = safePosition(player.serverLevel(), player.blockPosition());
        Vec3 recallPosition = recallPosition(player, destination);
        if (npc.level() == player.level()) {
            npc.getNavigation().stop();
            npc.setTarget(null);
            npc.stopRiding();
            npc.teleportTo(recallPosition.x, recallPosition.y, recallPosition.z);
            npc.setNoGravity(false);
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0;
            npc.setOnGround(player.onGround());
            npc.tasks().onRecalled();
            remember(player, npc);
            CodexNetwork.sendSnapshot(player, npc);
            return npc;
        }

        CompoundTag saved = new CompoundTag();
        npc.saveWithoutId(saved);
        UUID uuid = npc.getUUID();
        npc.remove(Entity.RemovalReason.CHANGED_DIMENSION);
        CodexNpcEntity moved = ModEntities.CODEX_NPC.get().create(player.serverLevel());
        if (moved == null) throw new IllegalStateException("Unable to move Codex NPC between dimensions");
        moved.load(saved);
        moved.setUUID(uuid);
        moved.setOwner(player);
        applyConfiguredName(moved);
        moved.moveTo(recallPosition.x, recallPosition.y, recallPosition.z, player.getYRot(), 0.0F);
        moved.setNoGravity(false);
        moved.setDeltaMovement(Vec3.ZERO);
        moved.fallDistance = 0;
        moved.setOnGround(player.onGround());
        moved.tasks().onRecalled();
        if (!player.serverLevel().addFreshEntity(moved)) {
            Entity conflict = player.serverLevel().getEntity(uuid);
            if (conflict instanceof CodexNpcEntity existing && existing.isOwnedBy(player)) {
                remember(player, existing);
                CodexNetwork.sendSnapshot(player, existing);
                return existing;
            }
            throw new IllegalStateException("Unable to move Codex NPC between dimensions");
        }
        remember(player, moved);
        CodexNetwork.sendSnapshot(player, moved);
        return moved;
    }

    public static void forget(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        data.remove(NPC_RECORD);
        data.remove(NPC_UUID);
        data.remove(NPC_BACKUP);
        data.remove(NPC_DIMENSION);
    }

    private static UUID remembered(ServerPlayer player) {
        CompoundTag record = npcRecord(player);
        return record.hasUUID(RECORD_UUID) ? record.getUUID(RECORD_UUID) : null;
    }

    private static boolean hasRememberedBackup(ServerPlayer player) {
        CompoundTag record = npcRecord(player);
        return record.hasUUID(RECORD_UUID) && record.contains(RECORD_BACKUP, Tag.TAG_COMPOUND);
    }

    private static void remember(ServerPlayer player, CodexNpcEntity npc) {
        CompoundTag saved = new CompoundTag();
        npc.saveWithoutId(saved);
        saved.putUUID("UUID", npc.getUUID());
        CompoundTag record = new CompoundTag();
        record.putInt(RECORD_VERSION, 1);
        record.putUUID(RECORD_UUID, npc.getUUID());
        record.put(RECORD_BACKUP, saved);
        record.putString(RECORD_DIMENSION, npc.level().dimension().location().toString());
        CompoundTag data = player.getPersistentData();
        data.put(NPC_RECORD, record);
        data.remove(NPC_UUID);
        data.remove(NPC_BACKUP);
        data.remove(NPC_DIMENSION);
    }

    private static CompoundTag npcRecord(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (data.contains(NPC_RECORD, Tag.TAG_COMPOUND)) return data.getCompound(NPC_RECORD);
        if (!data.hasUUID(NPC_UUID)) return new CompoundTag();

        CompoundTag record = new CompoundTag();
        record.putInt(RECORD_VERSION, 1);
        UUID uuid = data.getUUID(NPC_UUID);
        record.putUUID(RECORD_UUID, uuid);
        if (data.contains(NPC_BACKUP, Tag.TAG_COMPOUND)) {
            CompoundTag saved = data.getCompound(NPC_BACKUP).copy();
            saved.putUUID("UUID", uuid);
            record.put(RECORD_BACKUP, saved);
        }
        if (data.contains(NPC_DIMENSION, Tag.TAG_STRING)) {
            record.putString(RECORD_DIMENSION, data.getString(NPC_DIMENSION));
        }
        data.put(NPC_RECORD, record);
        data.remove(NPC_UUID);
        data.remove(NPC_BACKUP);
        data.remove(NPC_DIMENSION);
        return record;
    }

    private static CodexNpcEntity restoreRemembered(ServerPlayer player) {
        CompoundTag record = npcRecord(player);
        if (!record.hasUUID(RECORD_UUID) || !record.contains(RECORD_BACKUP, Tag.TAG_COMPOUND)) return null;

        UUID uuid = record.getUUID(RECORD_UUID);
        CompoundTag saved = record.getCompound(RECORD_BACKUP).copy();
        if (saved.hasUUID("UUID") && !saved.getUUID("UUID").equals(uuid)) {
            forget(player);
            return null;
        }
        saved.putUUID("UUID", uuid);
        ServerLevel targetLevel = null;
        String dimension = record.getString(RECORD_DIMENSION);
        for (ServerLevel level : player.server.getAllLevels()) {
            if (level.dimension().location().toString().equals(dimension)) {
                targetLevel = level;
                break;
            }
        }
        if (targetLevel == null) return null;

        ListTag position = saved.getList("Pos", Tag.TAG_DOUBLE);
        if (position.size() < 3) return null;
        BlockPos savedPosition = BlockPos.containing(
            position.getDouble(0),
            position.getDouble(1),
            position.getDouble(2)
        );
        targetLevel.getChunkAt(savedPosition);
        if (!targetLevel.areEntitiesLoaded(new ChunkPos(savedPosition).toLong())) return null;
        Entity loaded = targetLevel.getEntity(uuid);
        if (loaded instanceof CodexNpcEntity npc && npc.isOwnedBy(player)) return npc;

        CodexNpcEntity restored = ModEntities.CODEX_NPC.get().create(targetLevel);
        if (restored == null) return null;
        restored.load(saved);
        restored.setUUID(uuid);
        restored.setOwner(player);
        applyConfiguredName(restored);
        if (!targetLevel.addFreshEntity(restored)) {
            Entity conflict = targetLevel.getEntity(uuid);
            return conflict instanceof CodexNpcEntity npc && npc.isOwnedBy(player) ? npc : null;
        }
        remember(player, restored);
        return restored;
    }

    private static CodexNpcEntity findLoadedByUuid(ServerPlayer player, UUID uuid) {
        for (ServerLevel level : player.server.getAllLevels()) {
            Entity entity = level.getEntity(uuid);
            if (entity instanceof CodexNpcEntity npc && npc.isOwnedBy(player)) return npc;
        }
        return null;
    }

    private static void discardOwnedDuplicates(ServerPlayer player, CodexNpcEntity canonical) {
        List<CodexNpcEntity> duplicates = new ArrayList<>();
        for (ServerLevel level : player.server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof CodexNpcEntity npc
                    && npc != canonical
                    && npc.isOwnedBy(player)) duplicates.add(npc);
            }
        }
        for (CodexNpcEntity duplicate : duplicates) duplicate.discard();
    }

    private static void applyConfiguredName(CodexNpcEntity npc) {
        npc.setCustomName(Component.literal(CONFIG.name));
        npc.setCustomNameVisible(true);
    }

    static BlockPos safePosition(ServerLevel level, BlockPos ownerPosition) {
        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        if (isSafeRecallPosition(level, ownerPosition)) {
            best = ownerPosition;
            bestScore = 2.5D;
        }
        int[][] offsets = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {3, 1}, {-3, 1}, {1, 3}, {1, -3}
        };
        for (int[] offset : offsets) {
            BlockPos base = ownerPosition.offset(offset[0], 0, offset[1]);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos candidate = base.offset(0, dy, 0);
                if (!isSafeRecallPosition(level, candidate)) continue;
                double score = recallPositionScore(ownerPosition, candidate);
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        for (int radius = 4; radius <= 12; radius += 2) {
            for (int direction = 0; direction < 24; direction++) {
                double angle = direction * Math.PI / 12.0D;
                int dx = (int) Math.round(Math.cos(angle) * radius);
                int dz = (int) Math.round(Math.sin(angle) * radius);
                BlockPos base = ownerPosition.offset(dx, 0, dz);
                for (int dy = 4; dy >= -4; dy--) {
                    BlockPos candidate = base.offset(0, dy, 0);
                    if (!isSafeRecallPosition(level, candidate)) continue;
                    double score = recallPositionScore(ownerPosition, candidate);
                    if (score < bestScore) {
                        best = candidate;
                        bestScore = score;
                    }
                }
            }
        }
        for (int radius = 2; radius <= 16; radius += 2) {
            for (int direction = 0; direction < 24; direction++) {
                double angle = direction * Math.PI / 12.0D;
                int x = ownerPosition.getX() + (int) Math.round(Math.cos(angle) * radius);
                int z = ownerPosition.getZ() + (int) Math.round(Math.sin(angle) * radius);
                BlockPos column = new BlockPos(x, ownerPosition.getY(), z);
                if (!chunkLoaded(level, column)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                if (!isSafeRecallPosition(level, candidate)) continue;
                double score = recallPositionScore(ownerPosition, candidate);
                if (score < bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best == null ? ownerPosition.above() : best;
    }

    private static Vec3 recallPosition(ServerPlayer player, BlockPos destination) {
        if (Math.abs(destination.getY() - player.getY()) > 3.0D) return player.position();
        return new Vec3(destination.getX() + 0.5D, destination.getY(), destination.getZ() + 0.5D);
    }

    private static double recallPositionScore(BlockPos ownerPosition, BlockPos candidate) {
        double horizontal = Math.hypot(
            candidate.getX() - ownerPosition.getX(),
            candidate.getZ() - ownerPosition.getZ()
        );
        return horizontal + Math.abs(candidate.getY() - ownerPosition.getY()) * 8.0D;
    }

    private static boolean isSafeRecallPosition(ServerLevel level, BlockPos candidate) {
        if (!chunkLoaded(level, candidate)) return false;
        BlockState floor = level.getBlockState(candidate.below());
        BlockState feet = level.getBlockState(candidate);
        BlockState head = level.getBlockState(candidate.above());
        return floor.isSolidRender(level, candidate.below())
            && feet.getCollisionShape(level, candidate).isEmpty()
            && head.getCollisionShape(level, candidate.above()).isEmpty()
            && level.getFluidState(candidate).isEmpty()
            && level.getFluidState(candidate.above()).isEmpty();
    }

    private static boolean chunkLoaded(ServerLevel level, BlockPos position) {
        return level.getChunkSource().hasChunk(position.getX() >> 4, position.getZ() >> 4);
    }
}
