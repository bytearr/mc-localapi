package localapi.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("clickCount")
    int localapi$getClickCount();

    @Accessor("clickCount")
    void localapi$setClickCount(int v);

    @Accessor("key")
    com.mojang.blaze3d.platform.InputConstants.Key localapi$getKey();
}
