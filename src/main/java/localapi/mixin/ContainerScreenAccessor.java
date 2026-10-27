package localapi.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractContainerScreen.class)
public interface ContainerScreenAccessor {
    @Accessor("leftPos")
    int localapi$leftPos();

    @Accessor("topPos")
    int localapi$topPos();

    @Accessor("imageWidth")
    int localapi$imageWidth();

    @Accessor("imageHeight")
    int localapi$imageHeight();
}
