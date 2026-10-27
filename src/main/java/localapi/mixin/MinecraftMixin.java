package localapi.mixin;

import localapi.GameQueue;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    /** Every rendered frame: serve queued API calls (time-budgeted, never blocks on I/O). */
    @Inject(method = "runTick", at = @At("HEAD"))
    private void localapi$drain(boolean advanceGameTime, CallbackInfo ci) {
        GameQueue.drain();
    }

    /**
     * Vanilla calls pauseGame(false) from here once the window has been unfocused for 500 ms.
     * That opens the pause menu and, in singleplayer, pauses the integrated server
     * (it pauses only while the pause screen is open). Cancelling it fixes both.
     */
    @Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
    private void localapi$noPauseOnFocusLoss(CallbackInfo ci) {
        ci.cancel();
    }
}
