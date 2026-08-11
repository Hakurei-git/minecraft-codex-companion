package cn.codex.minecraftbridge.forge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/** Reversible food inventory state for a physical T-chat delivery acceptance. */
final class FoodDeliveryLiveFixture {
    private static final String MARKER_TAG = "CodexAcceptanceFoodMarker";
    private static final String ITEM_TAG = "CodexAcceptanceFoodItem";
    private static final String STATE_KEY = "CodexAcceptanceFoodState";
    private static final int SEARCH_LIMIT = 512;

    private FoodDeliveryLiveFixture() {
    }

    static void apply(ServerPlayer player, CodexNpcEntity npc, String mode) {
        if (npc == null) throw new IllegalStateException("Food delivery fixture requires an in-world NPC");
        switch (mode) {
            case "setup-player" -> setupPlayer(player, npc);
            case "inspect-player" -> inspectPlayer(player, npc);
            case "setup-home" -> setupHome(player, npc);
            case "inspect-home" -> inspectHome(player, npc);
            case "cleanup" -> cleanup(player, npc, true);
            default -> throw new IllegalArgumentException("Unknown food delivery fixture mode");
        }
    }

    private static void setupPlayer(ServerPlayer player, CodexNpcEntity npc) {
        cleanup(player, npc, false);
        ServerLevel level = player.serverLevel();
        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Food delivery fixture marker could not be created");
        marker.moveTo(npc.getX(), npc.getY() - 2.0D, npc.getZ(), 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putInt("FoodLevel", npc.foodLevel());
        state.putFloat("Saturation", npc.saturationLevel());
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) throw new IllegalStateException("Food delivery fixture marker was rejected");

        saveNpcFood(npc, state);
        marker.getPersistentData().put(STATE_KEY, state);

        ItemStack bread = new ItemStack(Items.BREAD, 8);
        bread.getOrCreateTag().putBoolean(ITEM_TAG, true);
        insertNpcStack(npc, bread);
        npc.setFoodLevel(20);
        npc.setSaturationLevel(5.0F);
        npc.setStatus("food-fixture:setup player=0,npc=8,world=0");
    }

    private static void setupHome(ServerPlayer player, CodexNpcEntity npc) {
        cleanup(player, npc, false);
        ServerLevel level = player.serverLevel();
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(player);
        if (!home.dimension().equals(level.dimension())) {
            throw new IllegalStateException("Food home fixture requires the player to be in the home dimension");
        }
        level.getChunkAt(home.position());
        BlockPos chestPosition = NpcHomeStorage.findSafePlacement(
            level,
            home,
            home.position(),
            10,
            position -> level.getBlockState(position).isAir() && isolatedChestCell(level, position)
        );
        if (chestPosition == null) throw new IllegalStateException("No reversible home chest position was found");
        BlockPos standPosition = NpcHomeStorage.findSafePlacement(
            level,
            home,
            chestPosition,
            4,
            position -> !position.equals(chestPosition)
                && level.getBlockState(position).isAir()
                && level.getBlockState(position.above()).isAir()
        );
        if (standPosition == null) throw new IllegalStateException("No safe NPC position was found beside the home chest");

        ArmorStand marker = EntityType.ARMOR_STAND.create(level);
        if (marker == null) throw new IllegalStateException("Food home fixture marker could not be created");
        marker.moveTo(chestPosition.getX() + 0.5D, chestPosition.getY() - 2.0D,
            chestPosition.getZ() + 0.5D, 0.0F, 0.0F);
        marker.setInvisible(true);
        marker.setInvulnerable(true);
        marker.setNoGravity(true);
        marker.addTag(MARKER_TAG);

        CompoundTag state = new CompoundTag();
        state.putInt("FoodLevel", npc.foodLevel());
        state.putFloat("Saturation", npc.saturationLevel());
        state.putBoolean("HomeFixture", true);
        state.putLong("HomeChest", chestPosition.asLong());
        state.putDouble("StartX", npc.getX());
        state.putDouble("StartY", npc.getY());
        state.putDouble("StartZ", npc.getZ());
        state.putFloat("StartYaw", npc.getYRot());
        state.putFloat("StartPitch", npc.getXRot());
        saveNpcFood(npc, state);
        marker.getPersistentData().put(STATE_KEY, state);
        if (!level.addFreshEntity(marker)) throw new IllegalStateException("Food home fixture marker was rejected");

        try {
            if (!level.setBlockAndUpdate(chestPosition, Blocks.CHEST.defaultBlockState())
                || !(level.getBlockEntity(chestPosition) instanceof Container)) {
                throw new IllegalStateException("Food home fixture chest could not be placed");
            }
            ItemStack bread = new ItemStack(Items.BREAD, 8);
            bread.getOrCreateTag().putBoolean(ITEM_TAG, true);
            insertNpcStack(npc, bread);
            npc.setFoodLevel(20);
            npc.setSaturationLevel(5.0F);
            npc.teleportTo(standPosition.getX() + 0.5D, standPosition.getY(), standPosition.getZ() + 0.5D);
            npc.getNavigation().stop();
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0.0F;
            npc.setStatus("food-fixture:setup-home home=0,npc=8,chest=ready");
        } catch (RuntimeException error) {
            cleanup(player, npc, false);
            throw error;
        }
    }

    private static void inspectPlayer(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = requireMarker(player, npc);
        int npcCount = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (isFixtureStack(stack)) npcCount += stack.getCount();
        }
        int playerCount = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (isFixtureStack(stack)) playerCount += stack.getCount();
        }
        var worldItems = player.serverLevel().getEntitiesOfClass(
            ItemEntity.class,
            marker.getBoundingBox().inflate(SEARCH_LIMIT),
            item -> isFixtureStack(item.getItem())
        );
        int worldCount = worldItems.stream().mapToInt(item -> item.getItem().getCount()).sum();
        int nearPlayerCount = worldItems.stream()
            .filter(item -> item.distanceToSqr(player) <= 2.5D * 2.5D)
            .mapToInt(item -> item.getItem().getCount())
            .sum();
        npc.tasks().stay();
        npc.setStatus("food-fixture:player=" + playerCount + ",npc=" + npcCount
            + ",world=" + worldCount + ",near=" + nearPlayerCount);
    }

    private static void inspectHome(ServerPlayer player, CodexNpcEntity npc) {
        requireMarker(player, npc);
        int npcCount = fixtureCountInNpc(npc);
        int homeCount = 0;
        int containers = 0;
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(player);
        for (BlockPos position : NpcHomeStorage.findContainers(player.serverLevel(), home, HomeStoragePolicy.DEFAULT_RADIUS)) {
            BlockEntity blockEntity = player.serverLevel().getBlockEntity(position);
            if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) continue;
            int contained = fixtureCount(container);
            if (contained > 0) containers++;
            homeCount += contained;
        }
        npc.tasks().stay();
        npc.setStatus("food-fixture:home=" + homeCount + ",npc=" + npcCount + ",containers=" + containers);
    }

    private static void cleanup(ServerPlayer player, CodexNpcEntity npc, boolean report) {
        ArmorStand marker = findMarker(player.serverLevel(), npc);
        removeFixtureStacks(player, npc);
        if (marker == null) {
            if (report) npc.setStatus("food-fixture:cleanup none");
            return;
        }
        for (ItemEntity item : player.serverLevel().getEntitiesOfClass(
            ItemEntity.class,
            marker.getBoundingBox().inflate(SEARCH_LIMIT),
            entity -> isFixtureStack(entity.getItem())
        )) item.discard();

        CompoundTag state = marker.getPersistentData().getCompound(STATE_KEY);
        removeFixtureStacksFromHome(player);
        if (state.getBoolean("HomeFixture")) {
            BlockPos chestPosition = BlockPos.of(state.getLong("HomeChest"));
            BlockEntity blockEntity = player.serverLevel().getBlockEntity(chestPosition);
            if (blockEntity instanceof Container container && containerIsEmpty(container)
                && player.serverLevel().getBlockState(chestPosition).is(Blocks.CHEST)) {
                player.serverLevel().setBlockAndUpdate(chestPosition, Blocks.AIR.defaultBlockState());
            }
        }
        ListTag savedFood = state.getList("SavedFood", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedFood.size(); index++) {
            CompoundTag entry = savedFood.getCompound(index);
            int slot = entry.getInt("Slot");
            ItemStack stack = ItemStack.of(entry.getCompound("Stack"));
            if (slot >= 0 && slot < CodexNpcEntity.INVENTORY_SIZE
                && npc.inventory().getStackInSlot(slot).isEmpty()) {
                npc.inventory().setStackInSlot(slot, stack);
            } else {
                insertNpcStack(npc, stack);
            }
        }
        npc.setFoodLevel(state.getInt("FoodLevel"));
        npc.setSaturationLevel(state.getFloat("Saturation"));
        if (state.getBoolean("HomeFixture")) {
            npc.teleportTo(state.getDouble("StartX"), state.getDouble("StartY"), state.getDouble("StartZ"));
            npc.setYRot(state.getFloat("StartYaw"));
            npc.setXRot(state.getFloat("StartPitch"));
            npc.setDeltaMovement(Vec3.ZERO);
            npc.fallDistance = 0.0F;
        }
        marker.discard();
        npc.tasks().followOwner();
        if (report) npc.setStatus("food-fixture:cleanup restored");
    }

    private static ArmorStand requireMarker(ServerPlayer player, CodexNpcEntity npc) {
        ArmorStand marker = findMarker(player.serverLevel(), npc);
        if (marker == null) throw new IllegalStateException("Food delivery fixture has not been set up");
        return marker;
    }

    private static ArmorStand findMarker(ServerLevel level, CodexNpcEntity npc) {
        return level.getEntitiesOfClass(
            ArmorStand.class,
            npc.getBoundingBox().inflate(SEARCH_LIMIT),
            candidate -> candidate.getTags().contains(MARKER_TAG)
        ).stream().min(Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
    }

    private static void removeFixtureStacks(ServerPlayer player, CodexNpcEntity npc) {
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            if (isFixtureStack(npc.inventory().getStackInSlot(slot))) npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (isFixtureStack(player.getInventory().getItem(slot))) player.getInventory().setItem(slot, ItemStack.EMPTY);
        }
    }

    private static void saveNpcFood(CodexNpcEntity npc, CompoundTag state) {
        ListTag savedFood = new ListTag();
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (stack.isEmpty() || stack.getFoodProperties(npc) == null) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("Slot", slot);
            entry.put("Stack", stack.save(new CompoundTag()));
            savedFood.add(entry);
            npc.inventory().setStackInSlot(slot, ItemStack.EMPTY);
        }
        state.put("SavedFood", savedFood);
    }

    private static int fixtureCountInNpc(CodexNpcEntity npc) {
        int count = 0;
        for (int slot = 0; slot < CodexNpcEntity.INVENTORY_SIZE; slot++) {
            ItemStack stack = npc.inventory().getStackInSlot(slot);
            if (isFixtureStack(stack)) count += stack.getCount();
        }
        return count;
    }

    private static int fixtureCount(Container container) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (isFixtureStack(stack)) count += stack.getCount();
        }
        return count;
    }

    private static void removeFixtureStacksFromHome(ServerPlayer player) {
        NpcHomeStorage.Home home = NpcHomeStorage.resolve(player);
        for (BlockPos position : NpcHomeStorage.findContainers(player.serverLevel(), home, HomeStoragePolicy.DEFAULT_RADIUS)) {
            BlockEntity blockEntity = player.serverLevel().getBlockEntity(position);
            if (!(blockEntity instanceof Container container) || blockEntity instanceof AbstractFurnaceBlockEntity) continue;
            boolean changed = false;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!isFixtureStack(container.getItem(slot))) continue;
                container.setItem(slot, ItemStack.EMPTY);
                changed = true;
            }
            if (changed) container.setChanged();
        }
    }

    private static boolean containerIsEmpty(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) return false;
        }
        return true;
    }

    private static boolean isolatedChestCell(ServerLevel level, BlockPos position) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(position.relative(direction)).is(Blocks.CHEST)
                || level.getBlockState(position.relative(direction)).is(Blocks.TRAPPED_CHEST)) return false;
        }
        return true;
    }

    private static boolean isFixtureStack(ItemStack stack) {
        return !stack.isEmpty() && stack.getTag() != null && stack.getTag().getBoolean(ITEM_TAG);
    }

    private static void insertNpcStack(CodexNpcEntity npc, ItemStack stack) {
        ItemStack remainder = stack;
        for (int slot = 0; slot < CodexNpcEntity.BACKPACK_SIZE && !remainder.isEmpty(); slot++) {
            remainder = npc.inventory().insertItem(slot, remainder, false);
        }
        if (!remainder.isEmpty()) throw new IllegalStateException("NPC backpack has no room for food delivery fixture state");
    }
}
