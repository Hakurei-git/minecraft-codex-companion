package cn.codex.minecraftbridge.forge;

import cn.codex.minecraftbridge.MinecraftCodexBridge;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MinecraftCodexBridge.MOD_ID);

    public static final RegistryObject<EntityType<CodexNpcEntity>> CODEX_NPC = ENTITY_TYPES.register(
        "codex_npc",
        () -> EntityType.Builder.of(CodexNpcEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .updateInterval(2)
            .build("codex_npc")
    );

    private ModEntities() {
    }
}
