package cn.codex.minecraftbridge.forge;

import net.minecraft.world.entity.Entity;

import java.util.List;

final class DragonAdapters {
    private static final List<DragonAdapter> SUPPORTED = List.of(
        new BookOfDragonsAdapter(),
        new SaintsDragonsAdapter()
    );

    private DragonAdapters() {}

    static DragonAdapter forEntity(Entity entity) {
        return SUPPORTED.stream().filter(adapter -> adapter.supports(entity)).findFirst().orElse(null);
    }
}
