package cn.codex.minecraftbridge.client.mixin;

import cn.codex.minecraftbridge.client.ClientDesktopSafetyLiveFixture;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps automated live tests from trapping the user's desktop cursor. */
@Mixin(MouseHandler.class)
public abstract class ClientMouseHandlerMixin {
    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void codex$suppressLiveFixtureMouseGrab(CallbackInfo callback) {
        if (ClientDesktopSafetyLiveFixture.suppressMouseGrab()) callback.cancel();
    }
}
