package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = MinecraftCodexBridge.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ForgeModEvents {
    private ForgeModEvents() {
    }

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.CODEX_NPC.get(), CodexNpcEntity.createAttributes().build());
    }
}
