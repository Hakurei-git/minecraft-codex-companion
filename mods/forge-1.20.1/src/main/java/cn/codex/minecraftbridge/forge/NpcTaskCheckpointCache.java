package cn.codex.minecraftbridge.forge;

/** Retains the last scheduler snapshot that passed bounded decoding. */
final class NpcTaskCheckpointCache {
    private byte[] lastValid = NpcTaskPersistence.encodeCompressed(NpcTaskPersistence.empty());

    byte[] remember(byte[] encoded) {
        NpcTaskPersistence.decodeCompressed(encoded);
        lastValid = encoded.clone();
        return lastValid.clone();
    }

    byte[] lastValid() {
        return lastValid.clone();
    }
}
