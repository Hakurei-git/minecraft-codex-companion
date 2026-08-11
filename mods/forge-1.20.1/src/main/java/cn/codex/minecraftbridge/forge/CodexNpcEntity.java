package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.client.BridgeConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.ToolActions;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CodexNpcEntity extends PathfinderMob implements MenuProvider {
    static final String DELIVERY_RECIPIENT_TAG = "CodexDeliveryRecipient";
    static final String DISCARDED_BY_TAG = "CodexDiscardedBy";
    public static final int BACKPACK_SIZE = 27;
    public static final int MAIN_HAND_SLOT = 27;
    public static final int OFF_HAND_SLOT = 28;
    public static final int HEAD_SLOT = 29;
    public static final int CHEST_SLOT = 30;
    public static final int LEGS_SLOT = 31;
    public static final int FEET_SLOT = 32;
    public static final int INVENTORY_SIZE = 33;
    private static final int DRAGON_DISMOUNT_PROTECTION_TICKS = 40;

    private static final EntityDataAccessor<Optional<UUID>> OWNER = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<Byte> STANCE = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Boolean> DOWNED = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> FOOD = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SATURATION = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<String> STATUS = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> ACTIVE_TASK_KIND = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> ACTIVE_TASK_PROGRESS = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> PAUSED_TASKS = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> PAUSE_REASON = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> MATERIAL_MODE = SynchedEntityData.defineId(CodexNpcEntity.class, EntityDataSerializers.STRING);

    private static final BridgeConfig CONFIG = BridgeConfig.load();

    private final ItemStackHandler inventory = new ItemStackHandler(INVENTORY_SIZE) {
        @Override
        protected void onContentsChanged(int slot) {
            syncEquipment();
        }
    };
    private final ItemTransactionLedger itemTransactions = new ItemTransactionLedger();
    private LazyOptional<ItemStackHandler> inventoryCapability = LazyOptional.of(() -> inventory);
    private final NpcTaskEngine tasks = new NpcTaskEngine(this, CONFIG);
    private final NpcTaskCheckpointCache taskCheckpointCache = new NpcTaskCheckpointCache();
    private int recoveryTicks;
    private float exhaustion;
    private int naturalRegenerationTicks;
    private int managedEatingSourceSlot = -1;
    private int eatingCompletionSequence;
    private String lastEatenName = "";
    private boolean automaticEatingUntilFull;
    private int clientSpeechTicks;
    private int dragonDismountProtectionTicks;
    private long lastServerCompanionGameTime = Long.MIN_VALUE;
    private String inventoryTransactionTaskOverride = "";
    private String inventoryTransactionActionOverride = "";
    private UUID boundDragonUuid;
    private String boundDragonDimension = "";
    private BlockPos boundDragonPosition;
    private long liveFixtureSequence;
    private String liveFixtureSuite = "";
    private String liveFixtureMode = "";
    private String liveFixtureStatus = "";
    private String nextLiveFixtureAckStatus;

    public CodexNpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        setCanPickUpLoot(true);
        setCustomName(Component.literal(CONFIG.name));
        setCustomNameVisible(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.30)
            .add(Attributes.ATTACK_DAMAGE, 4.0)
            .add(Attributes.ATTACK_SPEED, 4.0)
            .add(Attributes.FOLLOW_RANGE, 48.0)
            .add(Attributes.ARMOR, 0.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 10.0F));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(OWNER, Optional.empty());
        entityData.define(STANCE, NpcTaskEngine.Stance.FOLLOW.id());
        entityData.define(DOWNED, false);
        entityData.define(FOOD, 20);
        entityData.define(SATURATION, 5.0F);
        entityData.define(STATUS, "待命");
        entityData.define(ACTIVE_TASK_KIND, "");
        entityData.define(ACTIVE_TASK_PROGRESS, 0);
        entityData.define(PAUSED_TASKS, 0);
        entityData.define(PAUSE_REASON, "");
        entityData.define(MATERIAL_MODE, "生存");
    }

    public void setOwner(ServerPlayer player) {
        entityData.set(OWNER, Optional.of(player.getUUID()));
    }

    public Optional<UUID> ownerUuid() {
        return entityData.get(OWNER);
    }

    public boolean isOwnedBy(Player player) {
        return ownerUuid().filter(player.getUUID()::equals).isPresent();
    }

    @Nullable
    public ServerPlayer owner() {
        if (!(level().getServer() != null)) return null;
        return ownerUuid().map(level().getServer().getPlayerList()::getPlayer).orElse(null);
    }

    public NpcTaskEngine tasks() {
        return tasks;
    }

    public void rememberDragon(Entity dragon) {
        boundDragonUuid = dragon.getUUID();
        boundDragonDimension = dragon.level().dimension().location().toString();
        boundDragonPosition = dragon.blockPosition();
    }

    @Nullable
    public UUID boundDragonUuid() {
        return boundDragonUuid;
    }

    public String boundDragonDimension() {
        return boundDragonDimension;
    }

    @Nullable
    public BlockPos boundDragonPosition() {
        return boundDragonPosition;
    }

    public ItemStackHandler inventory() {
        return inventory;
    }

    public NpcTaskEngine.Stance stance() {
        return NpcTaskEngine.Stance.fromId(entityData.get(STANCE));
    }

    public void setStance(NpcTaskEngine.Stance stance) {
        entityData.set(STANCE, stance.id());
    }

    public boolean isDowned() {
        return entityData.get(DOWNED);
    }

    public int foodLevel() {
        return entityData.get(FOOD);
    }

    public void setFoodLevel(int value) {
        entityData.set(FOOD, Math.max(0, Math.min(20, value)));
        if (foodLevel() >= 20) automaticEatingUntilFull = false;
        if (saturationLevel() > foodLevel()) setSaturationLevel(foodLevel());
    }

    public float saturationLevel() {
        return entityData.get(SATURATION);
    }

    public void setSaturationLevel(float value) {
        entityData.set(SATURATION, Math.max(0.0F, Math.min(foodLevel(), value)));
    }

    public float exhaustionLevel() {
        return exhaustion;
    }

    void setExhaustionLevel(float value) {
        exhaustion = Math.max(0.0F, Math.min(4.0F, value));
    }

    public void feed(int nutrition, float saturationModifier) {
        int previousFood = foodLevel();
        setFoodLevel(previousFood + Math.max(0, nutrition));
        setSaturationLevel(saturationLevel() + Math.max(0, nutrition) * Math.max(0, saturationModifier) * 2.0F);
    }

    public String status() {
        return isDowned() ? "倒地恢复中" : entityData.get(STATUS);
    }

    public void setStatus(String value) {
        entityData.set(STATUS, value == null || value.isBlank() ? "待命" : value.substring(0, Math.min(120, value.length())));
    }

    public void recordLiveFixtureAck(String suite, String mode) {
        liveFixtureSequence++;
        liveFixtureSuite = boundedFixtureToken(suite);
        liveFixtureMode = boundedFixtureToken(mode);
        liveFixtureStatus = nextLiveFixtureAckStatus == null ? status() : nextLiveFixtureAckStatus;
        nextLiveFixtureAckStatus = null;
    }

    public void setNextLiveFixtureAckStatus(String value) {
        nextLiveFixtureAckStatus = value == null
            ? null
            : value.substring(0, Math.min(120, value.length()));
    }

    /**
     * Registry-matrix evidence can contain several complete resource
     * locations.  Keep it out of the 120-character entity display status,
     * but still apply a strict bound before exposing it through the local
     * loopback snapshot acknowledgement.
     */
    public void setNextLiveFixtureAckEvidence(String value) {
        nextLiveFixtureAckStatus = value == null
            ? null
            : value.substring(0, Math.min(2048, value.length()));
    }

    public long liveFixtureSequence() {
        return liveFixtureSequence;
    }

    public String liveFixtureSuite() {
        return liveFixtureSuite;
    }

    public String liveFixtureMode() {
        return liveFixtureMode;
    }

    public String liveFixtureStatus() {
        return liveFixtureStatus;
    }

    private static String boundedFixtureToken(String value) {
        if (value == null) return "";
        return value.substring(0, Math.min(64, value.length()));
    }

    public String activeTaskKindForDisplay() {
        return entityData.get(ACTIVE_TASK_KIND);
    }

    public int activeTaskProgressPercent() {
        return entityData.get(ACTIVE_TASK_PROGRESS);
    }

    public int pausedTaskCountForDisplay() {
        return entityData.get(PAUSED_TASKS);
    }

    public String pauseReasonForDisplay() {
        return entityData.get(PAUSE_REASON);
    }

    public String materialModeForDisplay() {
        return entityData.get(MATERIAL_MODE);
    }

    public void addExhaustion(float amount) {
        if (creativeResources()) return;
        exhaustion += Math.max(0, amount);
        while (exhaustion >= 4.0F) {
            exhaustion -= 4.0F;
            if (saturationLevel() > 0.0F) setSaturationLevel(saturationLevel() - 1.0F);
            else setFoodLevel(foodLevel() - 1);
        }
    }

    public boolean creativeResources() {
        if (CONFIG.npcMaterialMode.equals("creative")) return true;
        if (CONFIG.npcMaterialMode.equals("survival")) return false;
        ServerPlayer owner = owner();
        return owner != null && owner.getAbilities().instabuild;
    }

    @Override
    public void setNoAi(boolean noAi) {
        // Dragon mods may disable Mob AI while managing passengers. The
        // companion owns a persistent task scheduler, so that flag must never
        // survive dismounting or a world reload.
        super.setNoAi(false);
    }

    @Override
    public void tick() {
        super.tick();
        tickServerCompanion();
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (level().isClientSide) {
            if (clientSpeechTicks > 0) clientSpeechTicks--;
            return;
        }
        tickServerCompanion();
    }

    private void tickServerCompanion() {
        if (level().isClientSide) return;
        long gameTime = level().getGameTime();
        if (lastServerCompanionGameTime == gameTime) return;
        lastServerCompanionGameTime = gameTime;
        String ledgerTaskId = tasks.activeTaskId();
        String ledgerAction = tasks.activeTaskKind();
        if (ledgerAction.isBlank()) ledgerAction = isManagedEating() ? "auto-eat" : "inventory-change";
        try {
            if (dragonDismountProtectionTicks > 0) {
                dragonDismountProtectionTicks--;
                fallDistance = 0.0F;
            }
            if (isDowned()) {
                getNavigation().stop();
                setDeltaMovement(0, getDeltaMovement().y, 0);
                if (--recoveryTicks <= 0) recover();
                return;
            }
            tasks.tick();
            syncTaskPresentation();
            if (tickCount % 10 == 0) {
                if (!isManagedEating()) normalizeEquipmentSlots();
                tryStartAutomaticEating();
            }
            if (tickCount % 40 == 0 && !isManagedEating()) optimizeEquipment();
            tickNaturalRegeneration();
            if (foodLevel() <= 0 && tickCount % 80 == 0) hurt(damageSources().starve(), 1.0F);
        } finally {
            String observedTaskId = inventoryTransactionTaskOverride.isBlank()
                ? ledgerTaskId
                : inventoryTransactionTaskOverride;
            String observedAction = inventoryTransactionActionOverride.isBlank()
                ? ledgerAction
                : inventoryTransactionActionOverride;
            itemTransactions.observe(gameTime, observedTaskId, observedAction, inventoryTotals());
            inventoryTransactionTaskOverride = "";
            inventoryTransactionActionOverride = "";
        }
    }

    void markInventoryTransactionContext(String taskId, String action) {
        inventoryTransactionTaskOverride = taskId == null ? "" : taskId;
        inventoryTransactionActionOverride = action == null ? "" : action;
    }

    /**
     * Flushes one explicit inventory transition immediately.  This keeps two
     * different actions performed in the same game tick (for example loading
     * furnace input and fuel) as separate, truthful ledger entries instead of
     * assigning their combined delta to whichever context happened to run
     * last.
     */
    void recordInventoryTransaction(String taskId, String action) {
        itemTransactions.observe(
            level().getGameTime(),
            taskId == null ? "" : taskId,
            action == null ? "inventory-change" : action,
            inventoryTotals()
        );
        inventoryTransactionTaskOverride = "";
        inventoryTransactionActionOverride = "";
    }

    List<ItemTransactionLedger.Entry> recentItemTransactions() {
        return itemTransactions.recent();
    }

    private Map<String, Integer> inventoryTotals() {
        Map<String, Integer> totals = new HashMap<>();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            totals.merge(itemId, stack.getCount(), Integer::sum);
        }
        return totals;
    }

    void protectAfterDragonDismount() {
        dragonDismountProtectionTicks = Math.max(
            dragonDismountProtectionTicks,
            DRAGON_DISMOUNT_PROTECTION_TICKS
        );
        fallDistance = 0.0F;
    }

    @Override
    public boolean causeFallDamage(float distance, float damageMultiplier, DamageSource source) {
        if (dragonDismountProtectionTicks > 0) {
            fallDistance = 0.0F;
            return false;
        }
        return super.causeFallDamage(distance, damageMultiplier, source);
    }

    @Override
    public void rideTick() {
        super.rideTick();
        tickServerCompanion();
        DragonSharedRide.positionRearSeat(this);
    }

    private void syncTaskPresentation() {
        entityData.set(ACTIVE_TASK_KIND, tasks.activeTaskKind());
        entityData.set(ACTIVE_TASK_PROGRESS, (int) Math.round(tasks.activeTaskProgress() * 100.0D));
        entityData.set(PAUSED_TASKS, tasks.pausedTaskCount());
        entityData.set(PAUSE_REASON, tasks.primaryPauseReason());
        entityData.set(MATERIAL_MODE, creativeResources() ? "创造" : "生存");
    }

    public void triggerSpeechAnimation() {
        clientSpeechTicks = 40;
    }

    public int clientSpeechTicks() {
        return clientSpeechTicks;
    }

    private void tryStartAutomaticEating() {
        if (foodLevel() >= 20) {
            automaticEatingUntilFull = false;
            return;
        }
        if (!tasks.canStartAutomaticEating()) return;
        boolean needsRegenerationFood = HungerPolicy.shouldEatToRegenerate(foodLevel(), getHealth(), getMaxHealth());
        if (needsRegenerationFood) automaticEatingUntilFull = true;
        if (!automaticEatingUntilFull && foodLevel() >= HungerPolicy.AUTO_EAT_THRESHOLD) return;
        if (foodLevel() < HungerPolicy.AUTO_EAT_THRESHOLD) automaticEatingUntilFull = true;
        if (isUsingItem() || tasks.isExplicitEating()) return;
        int bestSlot = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            FoodProperties food = stack.getFoodProperties(this);
            if (stack.isEmpty() || food == null) continue;
            int score = food.getNutrition() * 10 + Math.round(food.getSaturationModifier() * 10.0F);
            if (!food.getEffects().isEmpty()) score -= 25;
            if (score > bestScore) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot >= 0) startManagedEating(bestSlot);
    }

    private void tickNaturalRegeneration() {
        if (!level().getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION)
            || !HungerPolicy.canNaturallyRegenerate(foodLevel(), getHealth(), getMaxHealth())) {
            naturalRegenerationTicks = 0;
            return;
        }
        if (++naturalRegenerationTicks < HungerPolicy.REGEN_INTERVAL_TICKS) return;
        naturalRegenerationTicks = 0;
        heal(1.0F);
        addExhaustion(6.0F);
    }

    public boolean startManagedEating(int sourceSlot) {
        if (sourceSlot < 0 || sourceSlot >= INVENTORY_SIZE || isUsingItem() || managedEatingSourceSlot >= 0) return false;
        ItemStack food = inventory.getStackInSlot(sourceSlot);
        if (food.isEmpty() || food.getFoodProperties(this) == null) return false;
        managedEatingSourceSlot = sourceSlot;
        if (sourceSlot != MAIN_HAND_SLOT) swapInventorySlots(sourceSlot, MAIN_HAND_SLOT);
        syncEquipment();
        setStatus("正在吃 " + inventory.getStackInSlot(MAIN_HAND_SLOT).getHoverName().getString());
        swing(InteractionHand.MAIN_HAND);
        startUsingItem(InteractionHand.MAIN_HAND);
        return true;
    }

    public void cancelManagedEating() {
        if (managedEatingSourceSlot < 0) return;
        stopUsingItem();
        restoreManagedEatingSlot();
    }

    public boolean isManagedEating() {
        return managedEatingSourceSlot >= 0;
    }

    public int eatingCompletionSequence() {
        return eatingCompletionSequence;
    }

    public String lastEatenName() {
        return lastEatenName;
    }

    @Override
    protected void completeUsingItem() {
        boolean managed = managedEatingSourceSlot >= 0;
        String eatenName = managed && !getUseItem().isEmpty() ? getUseItem().getHoverName().getString() : "";
        super.completeUsingItem();
        if (!managed) return;
        lastEatenName = eatenName;
        eatingCompletionSequence++;
        restoreManagedEatingSlot();
        syncEquipment();
    }

    @Override
    public ItemStack eat(Level level, ItemStack stack) {
        FoodProperties food = stack.getFoodProperties(this);
        ItemStack remaining = super.eat(level, stack);
        if (!level.isClientSide && food != null) feed(food.getNutrition(), food.getSaturationModifier());
        return remaining;
    }

    private void restoreManagedEatingSlot() {
        int sourceSlot = managedEatingSourceSlot;
        managedEatingSourceSlot = -1;
        if (sourceSlot >= 0 && sourceSlot != MAIN_HAND_SLOT) swapInventorySlots(sourceSlot, MAIN_HAND_SLOT);
    }

    private void swapInventorySlots(int first, int second) {
        ItemStack firstStack = inventory.getStackInSlot(first);
        ItemStack secondStack = inventory.getStackInSlot(second);
        inventory.setStackInSlot(first, secondStack);
        inventory.setStackInSlot(second, firstStack);
    }

    private void optimizeEquipment() {
        optimizeArmor();
        optimizeOffhand();
    }

    private void optimizeArmor() {
        for (EquipmentSlot equipmentSlot : new EquipmentSlot[] {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            int equipmentIndex = inventoryIndex(equipmentSlot);
            int bestSlot = equipmentIndex;
            double bestScore = equipmentScore(inventory.getStackInSlot(equipmentIndex), equipmentSlot);
            for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
                if (slot == equipmentIndex) continue;
                ItemStack candidate = inventory.getStackInSlot(slot);
                if (candidate.isEmpty() || !candidate.canEquip(equipmentSlot, this)) continue;
                double score = equipmentScore(candidate, equipmentSlot);
                if (score > bestScore + 0.001D) {
                    bestScore = score;
                    bestSlot = slot;
                }
            }
            if (bestSlot != equipmentIndex) swapInventorySlots(bestSlot, equipmentIndex);
        }
    }

    private void optimizeOffhand() {
        int bestSlot = OFF_HAND_SLOT;
        double bestScore = offhandScore(inventory.getStackInSlot(OFF_HAND_SLOT));
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (slot == OFF_HAND_SLOT) continue;
            double score = offhandScore(inventory.getStackInSlot(slot));
            if (score > bestScore + 0.001D) {
                bestScore = score;
                bestSlot = slot;
            }
        }
        if (bestSlot != OFF_HAND_SLOT) swapInventorySlots(bestSlot, OFF_HAND_SLOT);
    }

    private void normalizeEquipmentSlots() {
        for (EquipmentSlot equipmentSlot : new EquipmentSlot[] {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        }) {
            int equipmentIndex = inventoryIndex(equipmentSlot);
            ItemStack equipped = inventory.getStackInSlot(equipmentIndex);
            if (equipped.isEmpty() || equipped.canEquip(equipmentSlot, this)) continue;
            inventory.setStackInSlot(equipmentIndex, insert(equipped.copy()));
        }
    }

    private double equipmentScore(ItemStack stack, EquipmentSlot slot) {
        if (stack.isEmpty() || !stack.canEquip(slot, this)) return Double.NEGATIVE_INFINITY;
        double armor = stack.getAttributeModifiers(slot).get(Attributes.ARMOR).stream().mapToDouble(AttributeModifier::getAmount).sum();
        double toughness = stack.getAttributeModifiers(slot).get(Attributes.ARMOR_TOUGHNESS).stream().mapToDouble(AttributeModifier::getAmount).sum();
        int enchantmentScore = EnchantmentHelper.getEnchantments(stack).entrySet().stream()
            .mapToInt(entry -> (entry.getKey().isCurse() ? -8 : 2) * entry.getValue())
            .sum();
        double durability = stack.isDamageableItem()
            ? Math.max(0.0D, (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage())
            : 1.0D;
        return EquipmentPolicy.score(armor, toughness, enchantmentScore, durability);
    }

    private double offhandScore(ItemStack stack) {
        if (stack.isEmpty()) return Double.NEGATIVE_INFINITY;
        double durability = stack.isDamageableItem()
            ? Math.max(0.0D, (stack.getMaxDamage() - stack.getDamageValue()) / (double) stack.getMaxDamage())
            : 1.0D;
        double healthRatio = getMaxHealth() <= 0.0F ? 0.0D : getHealth() / (double) getMaxHealth();
        return EquipmentPolicy.offhandScore(
            stack.is(Items.TOTEM_OF_UNDYING),
            stack.canPerformAction(ToolActions.SHIELD_BLOCK),
            healthRatio,
            durability
        );
    }

    private int inventoryIndex(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> MAIN_HAND_SLOT;
            case OFFHAND -> OFF_HAND_SLOT;
            case HEAD -> HEAD_SLOT;
            case CHEST -> CHEST_SLOT;
            case LEGS -> LEGS_SLOT;
            case FEET -> FEET_SLOT;
        };
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        Entity directEntity = source.getDirectEntity();
        Entity causingEntity = source.getEntity();
        UUID ownerUuid = ownerUuid().orElse(null);
        ServerPlayer owner = owner();
        if (OwnerDamagePolicy.isOwnerOrOwnedDragonDamage(
            ownerUuid,
            directEntity == null ? null : directEntity.getUUID(),
            causingEntity == null ? null : causingEntity.getUUID(),
            isOwnedDragon(directEntity, owner),
            isOwnedDragon(causingEntity, owner)
        )) return false;
        if (isDowned()) return false;
        if (!level().isClientSide && amount >= getHealth()) {
            enterDowned();
            return true;
        }
        return super.hurt(source, amount);
    }

    private static boolean isOwnedDragon(@Nullable Entity entity, @Nullable ServerPlayer owner) {
        if (entity == null || owner == null) return false;
        DragonAdapter adapter = DragonAdapters.forEntity(entity);
        return adapter != null && adapter.isOwnedBy(entity, owner);
    }

    private void enterDowned() {
        setHealth(1.0F);
        entityData.set(DOWNED, true);
        recoveryTicks = CONFIG.npcRecoveryTicks;
        tasks.suspendForDowned();
        setStatus("倒地恢复中");
    }

    private void recover() {
        entityData.set(DOWNED, false);
        setHealth(Math.max(10.0F, getMaxHealth() * 0.5F));
        setFoodLevel(Math.max(10, foodLevel()));
        ServerPlayer owner = owner();
        if (owner != null && (owner.level() != level() || distanceToSqr(owner) > 24 * 24)) {
            NpcManager.recall(owner, this);
        }
        tasks.resumeAfterRecovery();
        if (tasks.activeTaskId().isBlank()) setStatus("已恢复");
    }

    @Override
    protected void pickUpItem(ItemEntity itemEntity) {
        if (level().isClientSide || itemEntity.getItem().isEmpty()) return;
        CompoundTag delivery = itemEntity.getPersistentData();
        if (delivery.hasUUID(DISCARDED_BY_TAG)
            && MiningInventoryCleanupPolicy.isDiscardedBy(
                getUUID(),
                delivery.getUUID(DISCARDED_BY_TAG)
            )) return;
        if (delivery.hasUUID(DELIVERY_RECIPIENT_TAG)
            && !delivery.getUUID(DELIVERY_RECIPIENT_TAG).equals(getUUID())) return;
        ItemStack remainder = insert(itemEntity.getItem().copy());
        if (remainder.isEmpty()) itemEntity.discard();
        else itemEntity.setItem(remainder);
    }

    public ItemStack insert(ItemStack stack) {
        ItemStack remainder = stack;
        for (int slot = 0; slot < BACKPACK_SIZE && !remainder.isEmpty(); slot++) {
            remainder = inventory.insertItem(slot, remainder, false);
        }
        return remainder;
    }

    public int absorbNearbyItems(double radius) {
        return absorbNearbyItemsAt(position(), radius);
    }

    public int absorbNearbyItemsAt(Vec3 center, double radius) {
        if (level().isClientSide) return 0;
        int absorbed = 0;
        AABB area = new AABB(
            center.x - radius, center.y - radius, center.z - radius,
            center.x + radius, center.y + radius, center.z + radius
        );
        for (ItemEntity itemEntity : level().getEntitiesOfClass(ItemEntity.class, area, entity -> {
            CompoundTag delivery = entity.getPersistentData();
            return entity.isAlive()
                && (!delivery.hasUUID(DISCARDED_BY_TAG)
                    || !MiningInventoryCleanupPolicy.isDiscardedBy(
                        getUUID(),
                        delivery.getUUID(DISCARDED_BY_TAG)
                    ))
                && (!delivery.hasUUID(DELIVERY_RECIPIENT_TAG)
                    || delivery.getUUID(DELIVERY_RECIPIENT_TAG).equals(getUUID()));
        })) {
            int before = itemEntity.getItem().getCount();
            ItemStack remainder = insert(itemEntity.getItem().copy());
            int moved = before - remainder.getCount();
            if (moved <= 0) continue;
            absorbed += moved;
            if (remainder.isEmpty()) itemEntity.discard();
            else itemEntity.setItem(remainder);
        }
        return absorbed;
    }

    private void syncEquipment() {
        if (level() == null) return;
        super.setItemSlot(EquipmentSlot.MAINHAND, inventory.getStackInSlot(MAIN_HAND_SLOT));
        super.setItemSlot(EquipmentSlot.OFFHAND, inventory.getStackInSlot(OFF_HAND_SLOT));
        super.setItemSlot(EquipmentSlot.HEAD, inventory.getStackInSlot(HEAD_SLOT));
        super.setItemSlot(EquipmentSlot.CHEST, inventory.getStackInSlot(CHEST_SLOT));
        super.setItemSlot(EquipmentSlot.LEGS, inventory.getStackInSlot(LEGS_SLOT));
        super.setItemSlot(EquipmentSlot.FEET, inventory.getStackInSlot(FEET_SLOT));
    }

    @Override
    public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
        int index = switch (slot) {
            case MAINHAND -> MAIN_HAND_SLOT;
            case OFFHAND -> OFF_HAND_SLOT;
            case HEAD -> HEAD_SLOT;
            case CHEST -> CHEST_SLOT;
            case LEGS -> LEGS_SLOT;
            case FEET -> FEET_SLOT;
        };
        inventory.setStackInSlot(index, stack);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!isOwnedBy(player)) {
            if (!level().isClientSide) player.displayClientMessage(Component.literal("只有绑定的玩家可以管理 Codex"), true);
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (player.isShiftKeyDown()) {
            if (!level().isClientSide) {
                NpcTaskEngine.Stance next = stance() == NpcTaskEngine.Stance.FOLLOW
                    ? NpcTaskEngine.Stance.STAY
                    : NpcTaskEngine.Stance.FOLLOW;
                if (next == NpcTaskEngine.Stance.FOLLOW) tasks.followOwner();
                else tasks.stay();
                player.displayClientMessage(Component.literal(next == NpcTaskEngine.Stance.FOLLOW ? "Codex 开始跟随" : "Codex 在这里等待"), true);
            }
            return InteractionResult.sidedSuccess(level().isClientSide);
        }
        if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, this, data -> data.writeInt(getId()));
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        return isOwnedBy(player) ? new CodexNpcMenu(containerId, playerInventory, this) : null;
    }

    @Override
    public Component getDisplayName() {
        return getCustomName() == null ? Component.literal(CONFIG.name) : getCustomName();
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ownerUuid().ifPresent(uuid -> tag.putUUID("CodexOwner", uuid));
        tag.putByte("CodexStance", stance().id());
        tag.putBoolean("CodexDowned", isDowned());
        tag.putInt("CodexRecoveryTicks", recoveryTicks);
        tag.putInt("CodexFood", foodLevel());
        tag.putFloat("CodexSaturation", saturationLevel());
        tag.putFloat("CodexExhaustion", exhaustion);
        tag.putInt("CodexNaturalRegenerationTicks", naturalRegenerationTicks);
        tag.putInt("CodexEatingCompletionSequence", eatingCompletionSequence);
        tag.putString("CodexLastEatenName", lastEatenName);
        tag.putBoolean("CodexAutomaticEatingUntilFull", automaticEatingUntilFull);
        tag.putString("CodexStatus", status());
        tag.put("CodexInventory", inventory.serializeNBT());
        tag.put("CodexItemTransactionLedger", itemTransactions.save());
        try {
            tag.putByteArray(
                "CodexTaskSchedulerV2",
                taskCheckpointCache.remember(tasks.savePersistentStateBytes())
            );
        } catch (IllegalArgumentException ignored) {
            String fallbackStatus = "当前任务存档过大或无效，已回退至上一次有效任务检查点";
            tag.putString("CodexStatus", fallbackStatus);
            tag.putByteArray("CodexTaskSchedulerV2", taskCheckpointCache.lastValid());
        }
        if (boundDragonUuid != null) tag.putUUID("CodexBoundDragon", boundDragonUuid);
        if (!boundDragonDimension.isBlank()) tag.putString("CodexBoundDragonDimension", boundDragonDimension);
        if (boundDragonPosition != null) tag.putLong("CodexBoundDragonPosition", boundDragonPosition.asLong());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        setNoAi(false);
        if (tag.hasUUID("CodexOwner")) entityData.set(OWNER, Optional.of(tag.getUUID("CodexOwner")));
        setStance(NpcTaskEngine.Stance.fromId(tag.getByte("CodexStance")));
        entityData.set(DOWNED, tag.getBoolean("CodexDowned"));
        recoveryTicks = tag.getInt("CodexRecoveryTicks");
        setFoodLevel(tag.contains("CodexFood") ? tag.getInt("CodexFood") : 20);
        setSaturationLevel(tag.contains("CodexSaturation") ? tag.getFloat("CodexSaturation") : Math.min(5.0F, foodLevel()));
        exhaustion = tag.getFloat("CodexExhaustion");
        naturalRegenerationTicks = Math.max(0, tag.getInt("CodexNaturalRegenerationTicks"));
        eatingCompletionSequence = Math.max(0, tag.getInt("CodexEatingCompletionSequence"));
        lastEatenName = tag.getString("CodexLastEatenName");
        automaticEatingUntilFull = tag.contains("CodexAutomaticEatingUntilFull")
            ? tag.getBoolean("CodexAutomaticEatingUntilFull")
            : foodLevel() < HungerPolicy.AUTO_EAT_THRESHOLD;
        setStatus(tag.getString("CodexStatus"));
        if (tag.contains("CodexInventory")) inventory.deserializeNBT(tag.getCompound("CodexInventory"));
        if (tag.contains("CodexItemTransactionLedger", Tag.TAG_COMPOUND)) {
            itemTransactions.load(tag.getCompound("CodexItemTransactionLedger"));
        }
        syncEquipment();
        try {
            if (tag.contains("CodexTaskSchedulerV2", Tag.TAG_BYTE_ARRAY)) {
                byte[] checkpoint = tag.getByteArray("CodexTaskSchedulerV2");
                tasks.loadPersistentState(checkpoint);
                taskCheckpointCache.remember(checkpoint);
            } else if (tag.contains("CodexTaskScheduler", Tag.TAG_STRING)) {
                tasks.loadPersistentState(tag.getString("CodexTaskScheduler"));
                taskCheckpointCache.remember(tasks.savePersistentStateBytes());
            }
        } catch (IllegalArgumentException ignored) {
            setStatus("任务存档损坏，已安全重置为跟随");
            setStance(NpcTaskEngine.Stance.FOLLOW);
        }
        boundDragonUuid = tag.hasUUID("CodexBoundDragon") ? tag.getUUID("CodexBoundDragon") : null;
        boundDragonDimension = tag.getString("CodexBoundDragonDimension");
        boundDragonPosition = tag.contains("CodexBoundDragonPosition")
            ? BlockPos.of(tag.getLong("CodexBoundDragonPosition"))
            : null;
        if (isDowned() && recoveryTicks <= 0) recoveryTicks = CONFIG.npcRecoveryTicks;
    }

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable net.minecraft.core.Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER && isAlive()) return inventoryCapability.cast();
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        inventoryCapability.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        inventoryCapability = LazyOptional.of(() -> inventory);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
