package localapi.mixin;

import localapi.ScreenWidgets;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class ScreenRevisionMixin {
    @Inject(method = {"init(II)V", "rebuildWidgets", "resize"}, at = @At("HEAD"))
    private void localapi$invalidate(CallbackInfo ci) { ScreenWidgets.invalidate(); }
}
