package cn.codex.minecraftbridge.client;

import java.nio.file.Path;
import java.util.Optional;

final class BridgeConfigWatcher {
    private final Path path;

    BridgeConfigWatcher(Path path) {
        this.path = path;
    }

    Optional<BridgeConfig> changedConfiguration(BridgeConfig active) {
        return BridgeConfig.readReady(path).filter(candidate -> !active.sameValues(candidate));
    }
}
