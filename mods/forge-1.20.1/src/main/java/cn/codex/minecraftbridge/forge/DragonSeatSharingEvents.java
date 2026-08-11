package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Lets the owner become the primary rider while their companion remains safely seated behind them. */
@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DragonSeatSharingEvents {
    private DragonSeatSharingEvents() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (startSharedRide(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (startSharedRide(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void protectAutopilotFromNavigationDamage(LivingHurtEvent event) {
        if (!isCompanionAutopilotEntity(event.getEntity())) return;
        if (event.getSource().is(DamageTypes.FALL)
            || event.getSource().is(DamageTypes.FLY_INTO_WALL)
            || event.getSource().is(DamageTypes.IN_WALL)
            || event.getSource().is(DamageTypes.CRAMMING)) event.setCanceled(true);
    }

    private static boolean startSharedRide(
        net.minecraft.world.entity.player.Player interactingPlayer,
        Entity interactedEntity
    ) {
        if (!(interactingPlayer instanceof ServerPlayer owner)) return false;
        boolean companionWasClicked = interactedEntity instanceof CodexNpcEntity;
        Entity dragon = companionWasClicked ? interactedEntity.getVehicle() : interactedEntity;
        if (dragon == null) return false;
        DragonAdapter adapter = DragonAdapters.forEntity(dragon);
        if (adapter == null || !adapter.isOwnedBy(dragon, owner)) return false;

        CodexNpcEntity companion = companionWasClicked
            ? (CodexNpcEntity) interactedEntity
            : dragon.getPassengers().stream()
                .filter(CodexNpcEntity.class::isInstance)
                .map(CodexNpcEntity.class::cast)
                .filter(npc -> npc.isOwnedBy(owner))
                .findFirst()
                .orElse(null);
        if (companion == null || !companion.isOwnedBy(owner)) return false;

        DragonSharedRide.MountResult result = DragonSharedRide.mountTogether(owner, companion, dragon, adapter);
        companion.setStatus(switch (result) {
            case MOUNTED, ALREADY_MOUNTED -> "正在与主人同骑（主人主驾）";
            case NOT_READY -> "这只龙还未满足双人骑乘条件";
            case FAILED -> "双人骑乘没有成功，正在保持当前座位";
        });
        CodexNetwork.sendSnapshot(owner, companion);
        return result.successful() || companionWasClicked;
    }

    private static boolean isCompanionAutopilotEntity(Entity entity) {
        if (entity instanceof CodexNpcEntity npc) {
            Entity vehicle = npc.getVehicle();
            return vehicle != null && DragonAdapters.forEntity(vehicle) != null;
        }
        if (DragonAdapters.forEntity(entity) == null) return false;
        return entity.getPassengers().stream().anyMatch(CodexNpcEntity.class::isInstance);
    }
}
