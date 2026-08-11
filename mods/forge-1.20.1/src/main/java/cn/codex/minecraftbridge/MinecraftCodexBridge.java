package cn.codex.minecraftbridge;

import cn.codex.minecraftbridge.forge.CodexNetwork;
import cn.codex.minecraftbridge.forge.ModEntities;
import cn.codex.minecraftbridge.forge.ModMenus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;

@Mod(MinecraftCodexBridge.MOD_ID)
public final class MinecraftCodexBridge {
    public static final String MOD_ID = "minecraft_codex_bridge";

    @SuppressWarnings("removal")
    public MinecraftCodexBridge() {
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITY_TYPES.register(modBus);
        ModMenus.MENU_TYPES.register(modBus);
        CodexNetwork.register();
    }
}
