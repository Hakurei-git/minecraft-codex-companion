package cn.codex.minecraftbridge.client;

import java.util.concurrent.CompletionStage;

interface BridgeChannel {
    boolean isOpen();

    CompletionStage<Void> sendText(String message);

    CompletionStage<Void> sendClose(int statusCode, String reason);
}
