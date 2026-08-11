package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import cn.codex.minecraftbridge.client.BridgeConfig;
import cn.codex.minecraftbridge.forge.client.ForgeNpcActor;
import com.mojang.logging.LogUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;

import java.util.function.Supplier;

@SuppressWarnings("deprecation")
public final class CodexNetwork {
    private static final String VERSION = "2";
    private static final int MAX_PAYLOAD = 1_048_576;
    private static final BridgeConfig CONFIG = BridgeConfig.load();
    private static final NpcSnapshotFactory SNAPSHOTS = new NpcSnapshotFactory();
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long BACKGROUND_PAUSE_LEASE_MILLIS = 30_000L;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(MinecraftCodexBridge.MOD_ID, "npc"),
        () -> VERSION,
        VERSION::equals,
        VERSION::equals
    );

    private CodexNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(ClientMessage.class, id++, NetworkDirection.PLAY_TO_SERVER)
            .encoder((message, buffer) -> {
                buffer.writeUtf(message.type, 40);
                buffer.writeUtf(message.payload, MAX_PAYLOAD);
            })
            .decoder(buffer -> new ClientMessage(buffer.readUtf(40), buffer.readUtf(MAX_PAYLOAD)))
            .consumerMainThread(CodexNetwork::handleClientMessage)
            .add();
        CHANNEL.messageBuilder(ServerMessage.class, id, NetworkDirection.PLAY_TO_CLIENT)
            .encoder((message, buffer) -> {
                buffer.writeUtf(message.type, 40);
                buffer.writeUtf(message.payload, MAX_PAYLOAD);
            })
            .decoder(buffer -> new ServerMessage(buffer.readUtf(40), buffer.readUtf(MAX_PAYLOAD)))
            .consumerMainThread(CodexNetwork::handleServerMessage)
            .add();
    }

    public static void sendToServer(String type, JsonObject payload) {
        CHANNEL.sendToServer(new ClientMessage(type, payload == null ? "{}" : payload.toString()));
    }

    public static void sendSnapshot(ServerPlayer player, CodexNpcEntity npc) {
        send(player, "snapshot", SNAPSHOTS.capture(npc, player, CONFIG));
    }

    public static void sendProgress(CodexNpcEntity npc, String taskId, double progress, String message) {
        sendProgress(npc, taskId, progress, message, "active", null);
    }

    public static void sendProgress(
        CodexNpcEntity npc,
        String taskId,
        double progress,
        String message,
        TaskProgressCounts counts
    ) {
        sendProgress(npc, taskId, progress, message, "active", counts);
    }

    public static void sendProgress(CodexNpcEntity npc, String taskId, double progress, String message, String phase) {
        sendProgress(npc, taskId, progress, message, phase, null);
    }

    public static void sendProgress(
        CodexNpcEntity npc,
        String taskId,
        double progress,
        String message,
        String phase,
        TaskProgressCounts counts
    ) {
        ServerPlayer owner = npc.owner();
        if (owner == null) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("taskId", taskId);
        payload.addProperty("progress", Math.max(0, Math.min(1, progress)));
        payload.addProperty("message", message);
        payload.addProperty("phase", phase);
        if (counts != null) {
            payload.addProperty("completedCount", counts.completedCount());
            payload.addProperty("targetCount", counts.targetCount());
            payload.addProperty("retainedCount", counts.retainedCount());
        }
        send(owner, "task-progress", payload);
    }

    public static void sendResult(CodexNpcEntity npc, String taskId, boolean ok, String message, String code) {
        sendResult(npc, taskId, ok, message, code, null);
    }

    public static void sendResult(
        CodexNpcEntity npc,
        String taskId,
        boolean ok,
        String message,
        String code,
        TaskProgressCounts counts
    ) {
        ServerPlayer owner = npc.owner();
        if (owner == null) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("taskId", taskId);
        payload.addProperty("ok", ok);
        payload.addProperty("message", message);
        if (code != null && !code.isBlank()) payload.addProperty("code", code);
        if (counts != null) {
            payload.addProperty("completedCount", counts.completedCount());
            payload.addProperty("targetCount", counts.targetCount());
            payload.addProperty("retainedCount", counts.retainedCount());
        }
        send(owner, "task-result", payload);
    }

    public static void sendSpeech(ServerPlayer player, String message) {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", CONFIG.name);
        payload.addProperty("message", message.substring(0, Math.min(256, message.length())));
        send(player, "speech", payload);
    }

    public static void sendBookDragonInputReset(ServerPlayer player, Entity dragon) {
        if (player == null || dragon == null) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("entityId", dragon.getId());
        payload.addProperty("dragonId", dragon.getUUID().toString());
        send(player, "book-dragon-input-reset", payload);
    }

    private static void send(ServerPlayer player, String type, JsonObject payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ServerMessage(type, payload.toString()));
    }

    private static void handleClientMessage(ClientMessage message, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player == null || !NpcManager.shouldOwnNpc(player)) return;
        JsonObject payload;
        try {
            payload = JsonParser.parseString(message.payload).getAsJsonObject();
        } catch (RuntimeException ignored) {
            return;
        }
        CodexNpcEntity npc = NpcManager.find(player);
        switch (message.type) {
            case "ensure" -> NpcManager.ensure(player);
            case "snapshot" -> {
                if (npc != null) sendSnapshot(player, npc);
            }
            case "run-task" -> {
                if (npc == null) npc = NpcManager.ensure(player);
                if (npc != null) npc.tasks().start(payload.getAsJsonObject("task"), payload.getAsJsonObject("buildPlan"));
            }
            case "cancel-task" -> {
                if (npc != null) npc.tasks().cancel(payload.get("taskId").getAsString(), payload.get("reason").getAsString());
            }
            case "stop" -> {
                if (npc != null) npc.tasks().emergencyStop("紧急停止");
            }
            case "speak" -> sendSpeech(player, payload.get("message").getAsString());
            case "live-fixture" -> runLiveFixture(player, npc, payload);
            case "recall" -> {
                npc = npc == null ? NpcManager.ensure(player) : NpcManager.recall(player, npc);
            }
            case "follow" -> {
                if (npc == null) npc = NpcManager.ensure(player);
                if (npc != null) {
                    npc.tasks().followOwner();
                    CodexNetwork.sendSnapshot(player, npc);
                }
            }
            case "stay" -> {
                if (npc != null) {
                    npc.tasks().stay();
                    CodexNetwork.sendSnapshot(player, npc);
                }
            }
            default -> {
            }
        }
        context.setPacketHandled(true);
    }

    private static void runLiveFixture(ServerPlayer player, CodexNpcEntity npc, JsonObject request) {
        String suite = request.has("suite") ? request.get("suite").getAsString() : "unknown";
        String mode = request.has("mode") ? request.get("mode").getAsString() : "unknown";
        boolean hasCheats = player.hasPermissions(2);
        LOGGER.info("Received live fixture {}:{} (cheats={})", suite, mode, hasCheats);
        if (!hasCheats && LiveFixturePolicy.requiresCheats(request)) {
            if (npc != null) {
                npc.setStatus("live-fixture:denied cheats-required suite=" + suite + " mode=" + mode);
                npc.recordLiveFixtureAck(suite, mode);
                sendSnapshot(player, npc);
            }
            sendSpeech(player, "实机测试动作需要当前世界开启作弊权限");
            return;
        }
        try {
            boolean showOutput = suite.equals("dragon")
                && (mode.equals("inspect-book-needs") || mode.equals("inspect-book-tame"));
            var source = player.createCommandSourceStack();
            if (!showOutput) source = source.withSuppressedOutput();
            var commands = LiveFixturePolicy.commands(request);
            if (hasCheats) {
                for (String command : commands) {
                    player.getServer().getCommands().performPrefixedCommand(source, command);
                }
            }
            applyLiveFixtureState(player, npc, suite, mode);
            CodexNpcEntity current = npc == null ? NpcManager.find(player) : npc;
            if (current != null) {
                current.recordLiveFixtureAck(suite, mode);
                sendSnapshot(player, current);
            }
        } catch (RuntimeException error) {
            LOGGER.warn("Live fixture {}:{} failed", suite, mode, error);
            CodexNpcEntity current = npc == null ? NpcManager.find(player) : npc;
            if (current != null) {
                String diagnostic = switch (suite) {
                    case "dragon" -> DragonLiveFixture.failureCode(error);
                    case "dragon-care" -> DragonCareLiveFixture.failureCode(error);
                    case "bed-sleep" -> BedSleepLiveFixture.failureCode(error);
                    case "no-cheat-expedition" -> NoCheatExpeditionLiveFixture.failureCode(error);
                    case "food-survival" -> FoodSurvivalLiveFixture.failureCode(error);
                    case "deep-mining" -> DeepMiningLiveFixture.failureCode(error);
                    case "player-state", "eating-action", "fishing-action", "farm-action", "guard-resume" ->
                        PlayerLifeLiveFixture.failureCode(error);
                    default -> "";
                };
                current.setStatus("live-fixture:failed suite=" + suite + " mode=" + mode
                    + (diagnostic.isBlank() ? "" : " code=" + diagnostic));
                current.recordLiveFixtureAck(suite, mode);
                sendSnapshot(player, current);
            }
            sendSpeech(player, "实机测试动作被拒绝或执行失败");
        }
    }

    private static void applyLiveFixtureState(
        ServerPlayer player,
        CodexNpcEntity npc,
        String suite,
        String mode
    ) {
        if (suite.equals("follow") && npc != null) {
            FollowResilienceLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("damage") && npc != null) {
            FollowResilienceLiveFixture.apply(
                player,
                npc,
                mode.equals("cleanup") ? "damage-cleanup" : mode
            );
            return;
        }
        if (suite.equals("ranch")) {
            RanchLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("food-delivery")) {
            FoodDeliveryLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("food-survival")) {
            FoodSurvivalLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("storage")) {
            StorageLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("no-cheat-expedition")) {
            NoCheatExpeditionLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("build-palette") || suite.equals("natural-tree")
            || suite.equals("build-material-chain")) {
            BuildGatherLiveFixture.apply(player, npc, suite, mode);
            return;
        }
        if (suite.equals("build-resume")) {
            BuildResumeLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("player-state") || suite.equals("eating-action") || suite.equals("fishing-action")
            || suite.equals("farm-action") || suite.equals("guard-resume")) {
            PlayerLifeLiveFixture.apply(player, npc, suite, mode);
            return;
        }
        if (suite.equals("craft-chain")) {
            CraftChainLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("resource-priority")) {
            ResourcePriorityLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("bed-sleep")) {
            BedSleepLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("deep-mining")) {
            DeepMiningLiveFixture.apply(player, npc, mode);
            return;
        }
        if (suite.equals("save-and-quit")) {
            JsonObject payload = new JsonObject();
            payload.addProperty("leaseMillis", BACKGROUND_PAUSE_LEASE_MILLIS);
            send(player, "background-pause-arm", payload);
            if (npc != null) {
                npc.setNextLiveFixtureAckStatus("save-and-quit:armed leaseMs=" + BACKGROUND_PAUSE_LEASE_MILLIS);
            }
            return;
        }
        if (suite.equals("dragon-care")) {
            DragonCareLiveFixture.apply(player, npc, mode);
            return;
        }
        if (!suite.equals("dragon") || npc == null) return;
        DragonLiveFixture.apply(player, npc, mode);
    }

    private static void mountFixtureDragonTogether(
        ServerPlayer player,
        CodexNpcEntity npc,
        String fixtureTag
    ) {
        Entity dragon = player.serverLevel().getEntities(
            npc,
            npc.getBoundingBox().inflate(256.0D),
            entity -> entity.isAlive() && entity.getTags().contains(fixtureTag)
        ).stream().min(java.util.Comparator.comparingDouble(npc::distanceToSqr)).orElse(null);
        if (dragon == null) throw new IllegalStateException("Fixture dragon is unavailable");
        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        DragonSharedRide.MountResult result = DragonSharedRide.mountTogether(player, npc, dragon, adapter);
        if (!result.successful()) throw new IllegalStateException("Fixture shared ride failed: " + result);
        npc.setStatus("正在与主人同骑（主人主驾）");
    }

    private static void armFixtureCombatTarget(ServerPlayer player) {
        LivingEntity target = player.serverLevel().getEntitiesOfClass(
            LivingEntity.class,
            player.getBoundingBox().inflate(64.0D),
            entity -> entity.isAlive() && entity.getTags().contains("CodexDragonCombatTarget")
        ).stream().min(java.util.Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        if (target == null) throw new IllegalStateException("Fixture combat target is unavailable");
        player.setLastHurtMob(target);
        target.setLastHurtByMob(player);
    }

    private static void spawnFixtureDragon(
        ServerPlayer player,
        CodexNpcEntity npc,
        String entityId,
        String fixtureTag,
        boolean maximizeHappiness
    ) {
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null) throw new IllegalArgumentException("Fixture dragon type is unavailable");
        Entity dragon = type.create(player.serverLevel());
        if (dragon == null) throw new IllegalStateException("Fixture dragon could not be created");
        dragon.moveTo(npc.getX() + 4.0D, npc.getY(), npc.getZ(), npc.getYRot(), 0.0F);
        dragon.addTag(fixtureTag);
        if (dragon instanceof Mob mob) {
            mob.finalizeSpawn(
                player.serverLevel(),
                player.serverLevel().getCurrentDifficultyAt(dragon.blockPosition()),
                MobSpawnType.COMMAND,
                null,
                null
            );
            mob.setPersistenceRequired();
        }
        if (!player.serverLevel().addFreshEntity(dragon)) {
            throw new IllegalStateException("Fixture dragon was rejected by the world");
        }
        if (dragon instanceof AgeableMob ageable) ageable.setAge(0);
        if (dragon instanceof TamableAnimal tameable) {
            // Calling a modded tame hook can run optional roost/event logic
            // before the synthetic fixture has finished initialization. The
            // actual persisted ownership source is the vanilla owner UUID;
            // set it directly, then configure each mod's additional flags.
            tameable.setOwnerUUID(player.getUUID());
            tameable.setOrderedToSit(false);
        }
        ReflectiveDragonAdapter.invokeVoid(dragon, "setOwnerUUID", player.getUUID());
        ReflectiveDragonAdapter.invokeVoid(dragon, "setTame", true);
        ReflectiveDragonAdapter.invokeVoid(dragon, "setTamed", true);
        if ("bookofdragons".equals(id.getNamespace())) {
            // Book of Dragons overrides TamableAnimal#isTame: an owner UUID is
            // not enough until its own ritual flag is complete. Give the
            // strictly local fixture a fully bonded state so ownership,
            // mounting, follow and combat checks exercise the real adapter.
            ReflectiveDragonAdapter.invokeVoid(dragon, "setTamingRitualCompleted", true);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setAwaitingTamingRitual", false);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setTamingRitualTimer", 0);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setAffection", player.getUUID(), 1000);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setGrowthStage", 2);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setGrowthProgress", 0);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setCommand", 2);
            Object inventory = ReflectiveDragonAdapter.invoke(dragon, "getInventory");
            if (inventory instanceof Container container && container.getContainerSize() > 0) {
                container.setItem(0, new ItemStack(Items.SADDLE));
                ReflectiveDragonAdapter.invokeVoid(dragon, "updateEquipment");
            }
            ReflectiveDragonAdapter.invokeVoid(dragon, "setSaddled", true);
            ReflectiveDragonAdapter.invokeVoid(dragon, "setSeatLocked", false);
        }
        if (maximizeHappiness) ReflectiveDragonAdapter.invokeVoid(dragon, "setHappiness", 100);
        if (dragon instanceof LivingEntity living) living.setHealth(living.getMaxHealth());
        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        if (adapter == null || !adapter.isOwnedBy(dragon, player)) {
            if (dragon instanceof TamableAnimal tameable) {
                Object ritual = ReflectiveDragonAdapter.invoke(dragon, "isTamingRitualCompleted");
                npc.setStatus("龙夹具所有权校验失败：ownerMatch="
                    + player.getUUID().equals(tameable.getOwnerUUID())
                    + "，tame=" + tameable.isTame()
                    + "，ritual=" + ritual);
            }
            dragon.discard();
            throw new IllegalStateException("Fixture dragon ownership initialization failed");
        }
        adapter.prepareSharedRide(dragon, player);
        npc.rememberDragon(dragon);
    }

    private static void handleServerMessage(ServerMessage message, Supplier<net.minecraftforge.network.NetworkEvent.Context> contextSupplier) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ForgeNpcActor.accept(message.type, message.payload));
        contextSupplier.get().setPacketHandled(true);
    }

    private record ClientMessage(String type, String payload) {
    }

    private record ServerMessage(String type, String payload) {
    }
}
