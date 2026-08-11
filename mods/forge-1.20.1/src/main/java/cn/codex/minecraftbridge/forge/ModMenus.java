package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
        DeferredRegister.create(ForgeRegistries.MENU_TYPES, MinecraftCodexBridge.MOD_ID);

    public static final RegistryObject<MenuType<CodexNpcMenu>> CODEX_NPC = MENU_TYPES.register(
        "codex_npc",
        () -> IForgeMenuType.create(CodexNpcMenu::fromNetwork)
    );

    private ModMenus() {
    }
}
