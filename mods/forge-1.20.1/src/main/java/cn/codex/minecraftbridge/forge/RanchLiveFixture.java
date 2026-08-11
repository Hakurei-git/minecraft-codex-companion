package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Reversible live proof that the in-world NPC builds a pen and performs every
 * ranch action. The fixture prepares only isolated terrain, supplies, and
 * tagged cattle; it never places a pen or changes the owner player.
 */
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
final class RanchLiveFixture {
    static final String ANIMAL_TAG = "CodexAcceptanceRanchAnimal";
    static final int PEN_BLOCK_COUNT = 32;

    private static final String MARKER_TAG = "CodexAcceptanceRanchMarker";
    private static final String ITEM_TAG = "CodexAcceptanceRanchItem";
    private static final String STATE_KEY = "CodexAcceptanceRanchState";
    private static final String SAVED_NPC_KEY = "SavedNpc";
    private static final String EXPECTED_BLOCKS_KEY = "ExpectedBlocks";
    private static final String PLACEMENT_POSITIONS_KEY = "PlacementPositions";
    private static final int SEARCH_LIMIT = 512;
    private static final int PLATFORM_MIN_X = -5;
    private static final int PLATFORM_MAX_X = 13;
    private static final int PLATFORM_MIN_Z = -12;
    private static final int PLATFORM_MAX_Z = 12;
    private static final int[] SITE_OFFSETS = { 64, -64, 96, -96, 128, -128 };

    private RanchLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Ranch fixture requires an in-world NPC");
        switch (mode) {
            case "setup-establish" -> setupEstablish(player, npc);
            case "supply-breed" -> supplyBreed(player, npc);
            case "setup-cull" -> setupCull(player, npc);
            case "inspect" -> inspect(player, npc);
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown ranch fixture mode");
        }
    }

    @SubscribeEvent
    public static void recordPlacement(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled()
            || !(event.getLevel() instanceof ServerLevel level)
            || !(event.getEntity() instanceof FakePlayer actor)) return;
        ArmorStand marker = markerNear(level, Vec3.atCenterOf(event.getPos()), 64.0D);
        if (marker == null) return;
        CompoundTag state = fixtureState(marker);
        BlockPos origin = BlockPos.of(state.getLong("PenOrigin"));
        if (!contains(state.getLongArray(EXPECTED_BLOCKS_KEY), event.getPos())) return;

        Entity entity = state.hasUUID("NpcUuid") ? level.getEntity(state.getUUID("NpcUuid")) : null;
        boolean actorMatches = entity instanceof CodexNpcEntity npc
            && state.hasUUID("ActorUuid")
            && actor.getUUID().equals(state.getUUID("ActorUuid"))
            && actor.position().distanceToSqr(npc.position()) <= 1.0E-6D;
        boolean blockMatches = expectedPenBlockMatches(origin, event.getPos(), event.getPlacedBlock());
        if (!actorMatches || !blockMatches) {
            state.putInt("PlacementViolations", state.getInt("PlacementViolations") + 1);
        } else if (!appendUnique(state, PLACEMENT_POSITIONS_KEY, event.getPos())) {
            state.putInt("PlacementViolations", state.getInt("PlacementViolations") + 1);
        }
        marker.getPersistentData().put(STATE_KEY, state);
    }

    private static void setupEstablish(ServerPlayer player, CodexNpcEntity npc) {
        cleanup(player, npc, false);
        requireSafeSetup(player, npc);
        ServerLevel level = player.serverLevel();
        BlockPos origin = findIsolatedOrigin(level, npc.blockPosition());
        List<BlockPos> platform = platformPositions(origin);
        List<BlockPos> expected = expectedPenPositions(origin);

        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Ranch fixture marker could not be created");
        marker.moveTo(origin.getX() + 4.5D, origin.getY() + 4.0D, origin.getZ() + 4.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putInt("Version", 2);
        state.putString("FixtureDimension", level.dimension().location().toString());
        state.putUUID("NpcUuid", npc.getUUID());
        ServerPlayer owner = npc.owner();
        state.putUUID("ActorUuid", owner == null ? npc.getUUID() : owner.getUUID());
        state.putLong("PenOrigin", origin.asLong());
        state.putLong("PenCenter", origin.offset(4, 0, 4).asLong());
        state.putLong("GateBlock", origin.offset(4, 0, 0).asLong());
        state.putLongArray("PlatformBlocks", longs(platform));
        state.putLongArray(EXPECTED_BLOCKS_KEY, longs(expected));
        state.putLongArray(PLACEMENT_POSITIONS_KEY, new long[0]);
        state.putInt("PlacementViolations", 0);
        state.put(SAVED_NPC_KEY, npc.saveWithoutId(new CompoundTag()));
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) {
            throw new IllegalStateException("Ranch fixture marker was rejected");
        }

        try {
            for (BlockPos position : platform) {
                set(level, position, Blocks.SEA_LANTERN.defaultBlockState());
            }
            clearInventory(npc);
            insertFixtureStack(npc, new ItemStack(Items.OAK_FENCE, 31));
            insertFixtureStack(npc, new ItemStack(Items.OAK_FENCE_GATE, 1));
            insertFixtureStack(npc, new ItemStack(Items.LEAD, 1));
            npc.cancelManagedEating();
            npc.setHealth(npc.getMaxHealth());
            npc.setFoodLevel(20);
            npc.setSaturationLevel(20.0F);
            npc.setExhaustionLevel(0.0F);
            npc.setNoGravity(false);
            BlockPos npcStart = origin.offset(-3, 0, -3);
            npc.teleportTo(npcStart.getX() + 0.5D, npcStart.getY(), npcStart.getZ() + 0.5D);
            npc.getNavigation().stop();
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0.0F;
            npc.tasks().stay();

            Cow first = spawnCow(level, origin.offset(4, 0, -7), true);
            Cow second = spawnCow(level, origin.offset(4, 0, -9), true);
            if (npc.getNavigation().createPath(first, 0) == null
                || npc.getNavigation().createPath(second, 0) == null) {
                throw new IllegalStateException("Ranch fixture cattle are not reachable by the NPC");
            }
            npc.setNextLiveFixtureAckStatus("ranch-fixture:setup");
        } catch (RuntimeException error) {
            cleanup(player, npc, false);
            throw error;
        }
    }

    private static void supplyBreed(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        tagFixtureBabies(player.serverLevel(), marker);
        for (Animal animal : fixtureAnimals(player.serverLevel(), marker)) {
            animal.setNoAi(false);
        }
        insertFixtureStack(npc, new ItemStack(Items.WHEAT, 2));
        npc.setNextLiveFixtureAckStatus("ranch-fixture:breed-supplied");
    }

    private static void setupCull(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        ServerLevel level = player.serverLevel();
        tagFixtureBabies(level, marker);
        CompoundTag state = fixtureState(marker);
        BlockPos center = BlockPos.of(state.getLong("PenCenter"));
        long adults = fixtureAnimals(level, marker).stream().filter(animal -> !animal.isBaby()).count();
        for (long index = adults; index < 3; index++) {
            spawnCow(level, center.offset((int) index - 1, 0, 0), false);
        }
        ItemStack weapon = new ItemStack(Items.NETHERITE_SWORD);
        weapon.getOrCreateTag().putBoolean(ITEM_TAG, true);
        weapon.getOrCreateTag().putBoolean("Unbreakable", true);
        weapon.enchant(Enchantments.SHARPNESS, 5);
        insertFixtureStack(npc, weapon);
        npc.setNextLiveFixtureAckStatus("ranch-fixture:cull-ready");
    }

    private static void inspect(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        ServerLevel level = player.serverLevel();
        tagFixtureBabies(level, marker);
        CompoundTag state = fixtureState(marker);
        BlockPos origin = BlockPos.of(state.getLong("PenOrigin"));
        List<Animal> animals = fixtureAnimals(level, marker);
        int adults = 0;
        int babies = 0;
        int inside = 0;
        for (Animal animal : animals) {
            if (animal.isBaby()) babies++;
            else adults++;
            if (insidePen(animal, origin)) inside++;
        }
        int blocks = countExpectedPenBlocks(level, origin);
        int placements = state.getLongArray(PLACEMENT_POSITIONS_KEY).length;
        int violations = state.getInt("PlacementViolations");
        BlockState gateState = level.getBlockState(origin.offset(4, 0, 0));
        String gate = gateStatus(gateState);
        boolean exactGate = gateState.is(Blocks.OAK_FENCE_GATE)
            && gateState.hasProperty(FenceGateBlock.FACING)
            && gateState.hasProperty(FenceGateBlock.OPEN)
            && expectedGateContract(
                gateState.getValue(FenceGateBlock.FACING).getName(),
                gateState.getValue(FenceGateBlock.OPEN)
            );
        npc.setNextLiveFixtureAckStatus(inspectionStatus(
            adults,
            babies,
            inside,
            animals.size() - inside,
            blocks,
            placements,
            violations + (gateState.is(Blocks.OAK_FENCE_GATE) && !exactGate ? 1 : 0),
            gate
        ));
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        ArmorStand marker = findMarker(player.serverLevel(), npc);
        if (marker == null) {
            if (report) npc.setNextLiveFixtureAckStatus("ranch-fixture:cleanup none");
            return;
        }
        CompoundTag state = fixtureState(marker);
        ServerLevel level = player.serverLevel();
        if (!level.dimension().location().toString().equals(state.getString("FixtureDimension"))) {
            throw new IllegalStateException("Ranch fixture cleanup requires the original dimension");
        }

        for (Entity entity : level.getEntities(
            marker,
            marker.getBoundingBox().inflate(SEARCH_LIMIT),
            candidate -> candidate.getTags().contains(ANIMAL_TAG)
        )) entity.discard();
        for (ItemEntity item : level.getEntitiesOfClass(
            ItemEntity.class,
            fixtureBounds(state).inflate(16.0D)
        )) item.discard();

        for (long packed : state.getLongArray(EXPECTED_BLOCKS_KEY)) {
            BlockPos position = BlockPos.of(packed);
            if (!level.getBlockState(position).isAir()) {
                level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
            }
        }
        for (long packed : state.getLongArray("PlatformBlocks")) {
            BlockPos position = BlockPos.of(packed);
            if (!level.getBlockState(position).isAir()) {
                level.setBlockAndUpdate(position, Blocks.AIR.defaultBlockState());
            }
        }

        boolean blocksRestored = allAir(level, state.getLongArray(EXPECTED_BLOCKS_KEY))
            && allAir(level, state.getLongArray("PlatformBlocks"));
        boolean entitiesRestored = level.getEntities(
            marker,
            fixtureBounds(state).inflate(16.0D),
            candidate -> candidate.getTags().contains(ANIMAL_TAG)
        ).isEmpty();
        if (!state.contains(SAVED_NPC_KEY, Tag.TAG_COMPOUND)) {
            throw new IllegalStateException("Ranch fixture NPC snapshot is missing");
        }
        CompoundTag savedNpc = state.getCompound(SAVED_NPC_KEY);
        npc.load(savedNpc.copy());
        npc.getNavigation().stop();
        boolean npcRestored = npc.saveWithoutId(new CompoundTag()).equals(savedNpc);
        boolean restored = blocksRestored && entitiesRestored && npcRestored;
        if (restored) marker.discard();
        if (report) {
            npc.setNextLiveFixtureAckStatus(restored
                ? "ranch-fixture:cleanup restored"
                : "ranch-fixture:cleanup incomplete");
        }
        if (!restored && !report) {
            throw new IllegalStateException("Ranch fixture cleanup restoration failed");
        }
    }

    static List<BlockPos> expectedPenPositions(BlockPos origin) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        for (int x = 0; x < 9; x++) {
            positions.add(origin.offset(x, 0, 0));
            positions.add(origin.offset(x, 0, 8));
        }
        for (int z = 1; z < 8; z++) {
            positions.add(origin.offset(0, 0, z));
            positions.add(origin.offset(8, 0, z));
        }
        return List.copyOf(positions);
    }

    static boolean expectedGateContract(String facing, boolean open) {
        return Direction.SOUTH.getName().equals(facing) && !open;
    }

    static boolean builtEvidence(int blocks, int placements, int violations, String gate) {
        return blocks == PEN_BLOCK_COUNT
            && placements == PEN_BLOCK_COUNT
            && violations == 0
            && "closed".equals(gate);
    }

    static String inspectionStatus(
        int adults,
        int babies,
        int inside,
        int outside,
        int blocks,
        int placements,
        int violations,
        String gate
    ) {
        int built = builtEvidence(blocks, placements, violations, gate) ? 1 : 0;
        return "ranch-fixture:adults=" + adults
            + ",babies=" + babies
            + ",inside=" + inside
            + ",outside=" + outside
            + ",built=" + built
            + ",blocks=" + blocks
            + ",placements=" + placements
            + ",gate=" + gate;
    }

    private static boolean expectedPenBlockMatches(BlockPos origin, BlockPos position, BlockState state) {
        if (position.equals(origin.offset(4, 0, 0))) return state.is(Blocks.OAK_FENCE_GATE);
        return expectedPenPositions(origin).contains(position) && state.is(Blocks.OAK_FENCE);
    }

    private static int countExpectedPenBlocks(ServerLevel level, BlockPos origin) {
        int count = 0;
        for (BlockPos position : expectedPenPositions(origin)) {
            if (expectedPenBlockMatches(origin, position, level.getBlockState(position))) count++;
        }
        return count;
    }

    private static String gateStatus(BlockState state) {
        if (!state.is(Blocks.OAK_FENCE_GATE)) return "missing";
        return state.hasProperty(FenceGateBlock.OPEN) && state.getValue(FenceGateBlock.OPEN)
            ? "open"
            : "closed";
    }

    private static boolean insidePen(Animal animal, BlockPos origin) {
        return RanchPenPolicy.insideBoundary(
            animal.getX(),
            animal.getZ(),
            origin.getX(),
            origin.getX() + 8,
            origin.getZ(),
            origin.getZ() + 8
        ) && Math.abs(animal.getY() - origin.getY()) <= 2.5D;
    }

    private static BlockPos findIsolatedOrigin(ServerLevel level, BlockPos near) {
        for (int dx : SITE_OFFSETS) {
            for (int dz : SITE_OFFSETS) {
                int x0 = near.getX() + dx;
                int z0 = near.getZ() + dz;
                int surface = level.getMinBuildHeight();
                boolean inBorder = true;
                for (int x = PLATFORM_MIN_X; x <= PLATFORM_MAX_X && inBorder; x++) {
                    for (int z = PLATFORM_MIN_Z; z <= PLATFORM_MAX_Z; z++) {
                        BlockPos column = new BlockPos(x0 + x, near.getY(), z0 + z);
                        if (!level.getWorldBorder().isWithinBounds(column)) {
                            inBorder = false;
                            break;
                        }
                        surface = Math.max(surface, level.getHeight(
                            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                            column.getX(),
                            column.getZ()
                        ));
                    }
                }
                if (!inBorder) continue;
                BlockPos origin = new BlockPos(x0, surface + 32, z0);
                if (origin.getY() + 4 >= level.getMaxBuildHeight()) continue;
                boolean clear = true;
                for (int x = PLATFORM_MIN_X; x <= PLATFORM_MAX_X && clear; x++) {
                    for (int z = PLATFORM_MIN_Z; z <= PLATFORM_MAX_Z && clear; z++) {
                        for (int y = -1; y <= 3; y++) {
                            BlockPos position = origin.offset(x, y, z);
                            if (!level.getBlockState(position).isAir()
                                || !level.getFluidState(position).isEmpty()) {
                                clear = false;
                                break;
                            }
                        }
                    }
                }
                if (!clear) continue;
                CompoundTag probe = new CompoundTag();
                probe.putLong("PenOrigin", origin.asLong());
                if (level.getEntities(null, fixtureBounds(probe)).isEmpty()) return origin;
            }
        }
        throw new IllegalStateException("No isolated ranch fixture site was found");
    }

    private static List<BlockPos> platformPositions(BlockPos origin) {
        List<BlockPos> positions = new ArrayList<>();
        for (int x = PLATFORM_MIN_X; x <= PLATFORM_MAX_X; x++) {
            for (int z = PLATFORM_MIN_Z; z <= PLATFORM_MAX_Z; z++) {
                positions.add(origin.offset(x, -1, z));
            }
        }
        return positions;
    }

    private static Cow spawnCow(ServerLevel level, BlockPos position, boolean frozen) {
        Cow cow = EntityType.COW.create(level);
        if (cow == null) throw new IllegalStateException("Ranch fixture cow could not be created");
        cow.moveTo(position.getX() + 0.5D, position.getY(), position.getZ() + 0.5D, 0.0F, 0.0F);
        cow.setAge(0);
        cow.setPersistenceRequired();
        cow.addTag(ANIMAL_TAG);
        cow.finalizeSpawn(level, level.getCurrentDifficultyAt(position), MobSpawnType.COMMAND, null, null);
        cow.setNoAi(frozen);
        if (!level.addFreshEntity(cow)) throw new IllegalStateException("Ranch fixture cow was rejected");
        return cow;
    }

    private static void tagFixtureBabies(ServerLevel level, ArmorStand marker) {
        CompoundTag state = fixtureState(marker);
        BlockPos origin = BlockPos.of(state.getLong("PenOrigin"));
        AABB pen = new AABB(
            origin.getX(),
            origin.getY() - 2,
            origin.getZ(),
            origin.getX() + 9,
            origin.getY() + 4,
            origin.getZ() + 9
        );
        for (Animal animal : level.getEntitiesOfClass(
            Animal.class,
            pen,
            candidate -> candidate.isBaby() && !candidate.hasCustomName()
        )) animal.addTag(ANIMAL_TAG);
    }

    private static List<Animal> fixtureAnimals(ServerLevel level, ArmorStand marker) {
        return level.getEntitiesOfClass(
            Animal.class,
            marker.getBoundingBox().inflate(SEARCH_LIMIT),
            animal -> animal.isAlive() && animal.getTags().contains(ANIMAL_TAG)
        );
    }

    private static void requireSafeSetup(ServerPlayer player, CodexNpcEntity npc) {
        if (player.level() != npc.level()) {
            throw new IllegalStateException("Ranch fixture requires owner and NPC in the same dimension");
        }
        if (!player.isAlive() || !npc.isAlive() || npc.isDowned()) {
            throw new IllegalStateException("Ranch fixture requires living owner and NPC actors");
        }
        String active = npc.tasks().activeTaskId();
        if ((active != null && !active.isBlank())
            || npc.tasks().pausedTaskCount() > 0
            || !"idle".equals(npc.tasks().schedulerLifecycle())) {
            throw new IllegalStateException("Ranch fixture requires an idle NPC scheduler");
        }
        if (npc.isManagedEating()) {
            throw new IllegalStateException("Ranch fixture requires NPC eating to finish");
        }
        if (npc.creativeResources()) {
            throw new IllegalStateException("Ranch fixture requires survival material mode");
        }
        if (npc.isPassenger() || !npc.getPassengers().isEmpty()) {
            throw new IllegalStateException("Ranch fixture requires a dismounted NPC");
        }
    }

    private static ArmorStand requireMarker(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = findMarker(player.serverLevel(), npc);
        if (marker == null) throw new IllegalStateException("Ranch fixture has not been set up");
        return marker;
    }

    private static ArmorStand findMarker(ServerLevel level, CodexNpcEntity npc) {
        return level.getEntitiesOfClass(
            ArmorStand.class,
            npc.getBoundingBox().inflate(SEARCH_LIMIT),
            marker -> marker.getTags().contains(MARKER_TAG)
        ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
    }

    private static ArmorStand markerNear(ServerLevel level, Vec3 position, double radius) {
        return level.getEntitiesOfClass(
            ArmorStand.class,
            new AABB(position, position).inflate(radius),
            marker -> marker.getTags().contains(MARKER_TAG)
        ).stream().min(Comparator.comparingDouble(marker -> marker.distanceToSqr(position))).orElse(null);
    }

    private static CompoundTag fixtureState(ArmorStand marker) {
        return marker.getPersistentData().getCompound(STATE_KEY);
    }

    private static AABB fixtureBounds(CompoundTag state) {
        BlockPos origin = BlockPos.of(state.getLong("PenOrigin"));
        return new AABB(
            origin.getX() + PLATFORM_MIN_X,
            origin.getY() - 2,
            origin.getZ() + PLATFORM_MIN_Z,
            origin.getX() + PLATFORM_MAX_X + 1,
            origin.getY() + 5,
            origin.getZ() + PLATFORM_MAX_Z + 1
        );
    }

    private static void insertFixtureStack(CodexNpcEntity npc, ItemStack stack) {
        stack.getOrCreateTag().putBoolean(ITEM_TAG, true);
        ItemStack remainder = stack;
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE && !remainder.isEmpty(); slot++) {
            remainder = npc.inventory().insertItem(slot, remainder, false);
        }
        if (!remainder.isEmpty()) {
            throw new IllegalStateException("NPC backpack has no room for ranch fixture supplies");
        }
    }

    private static void clearInventory(CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
    }

    private static void set(ServerLevel level, BlockPos position, BlockState state) {
        if (!level.setBlockAndUpdate(position, state)) {
            throw new IllegalStateException("Ranch fixture block could not be placed at " + position.toShortString());
        }
    }

    private static long[] longs(Iterable<BlockPos> positions) {
        List<Long> packed = new ArrayList<>();
        for (BlockPos position : positions) packed.add(position.asLong());
        long[] values = new long[packed.size()];
        for (int index = 0; index < packed.size(); index++) values[index] = packed.get(index);
        return values;
    }

    private static boolean contains(long[] values, BlockPos position) {
        long packed = position.asLong();
        for (long value : values) if (value == packed) return true;
        return false;
    }

    private static boolean appendUnique(CompoundTag state, String key, BlockPos position) {
        long[] values = state.getLongArray(key);
        if (contains(values, position)) return false;
        long[] expanded = Arrays.copyOf(values, values.length + 1);
        expanded[values.length] = position.asLong();
        state.putLongArray(key, expanded);
        return true;
    }

    private static boolean allAir(ServerLevel level, long[] positions) {
        for (long packed : positions) {
            if (!level.getBlockState(BlockPos.of(packed)).isAir()) return false;
        }
        return true;
    }
}
