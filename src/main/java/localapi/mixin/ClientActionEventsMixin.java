package localapi.mixin;

import localapi.ClientEvents;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public abstract class ClientActionEventsMixin {
    @Inject(method = {"handleContainerInput", "handleCreativeModeItemAdd", "handleCreativeModeItemDrop", "dropItem"}, at = @At("TAIL"))
    private void localapi$inventory(CallbackInfo ci) {
        ClientEvents.invalidate("/inventory", "/container", "/hand", "/player", "/screen");
    }
}
