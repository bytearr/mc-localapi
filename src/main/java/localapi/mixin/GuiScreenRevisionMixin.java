package localapi.mixin;

import localapi.ScreenWidgets;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class GuiScreenRevisionMixin {
    @Inject(method = {"setScreen", "setOverlay"}, at = @At("HEAD"))
    private void localapi$invalidate(CallbackInfo ci) { ScreenWidgets.invalidate(); }
}
