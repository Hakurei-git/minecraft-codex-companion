package cn.codex.minecraftbridge.forge;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;

final class BuildStatePolicy {
    interface StateAccess<S> {
        String value(S state, String propertyName);

        S withValue(S state, String propertyName, String value);
    }

    private static final StateAccess<BlockState> MINECRAFT_ACCESS = new StateAccess<>() {
        @Override
        public String value(BlockState state, String propertyName) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
            return property == null ? null : propertyValue(state, property);
        }

        @Override
        public BlockState withValue(BlockState state, String propertyName, String value) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(propertyName);
            if (property == null) throw new IllegalArgumentException("方块不支持属性 " + propertyName);
            return applyValue(state, property, value);
        }
    };

    private BuildStatePolicy() {
    }

    static boolean matches(BlockState state, Map<String, String> properties) {
        return matches(state, properties, MINECRAFT_ACCESS);
    }

    static <S> boolean matches(S state, Map<String, String> properties, StateAccess<S> access) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String current = access.value(state, entry.getKey());
            if (current == null || !current.equals(entry.getValue())) return false;
        }
        return true;
    }

    static BlockState apply(BlockState state, Map<String, String> properties) {
        return apply(state, properties, MINECRAFT_ACCESS);
    }

    static <S> S apply(S state, Map<String, String> properties, StateAccess<S> access) {
        S result = state;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            result = access.withValue(result, entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static <T extends Comparable<T>> String propertyValue(
        BlockState state,
        Property<T> property
    ) {
        return property.getName(state.getValue(property));
    }

    private static <T extends Comparable<T>> BlockState applyValue(
        BlockState state,
        Property<T> property,
        String value
    ) {
        T parsed = property.getValue(value).orElseThrow(
            () -> new IllegalArgumentException("属性 " + property.getName() + " 不支持值 " + value)
        );
        return state.setValue(property, parsed);
    }
}
