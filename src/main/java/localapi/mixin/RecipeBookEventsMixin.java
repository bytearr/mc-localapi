package localapi.mixin;

import localapi.ClientEvents;
import localapi.ScreenWidgets;
import net.minecraft.client.ClientRecipeBook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientRecipeBook.class)
public abstract class RecipeBookEventsMixin {
    @Inject(method = {"add", "remove", "clear"}, at = @At("HEAD"))
    private void localapi$recipesChanged(CallbackInfo ci) {
        ScreenWidgets.invalidate(); // Display IDs can be reused after a reset.
        ClientEvents.invalidate("/recipes");
    }
}
