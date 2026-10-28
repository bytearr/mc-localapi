package localapi.mixin;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(AccessibilityOptionsScreen.class)
public interface AccessibilityOptionsScreenInvoker {
    @Invoker("options") static OptionInstance<?>[] localapi$options(Options options) { throw new AssertionError(); }
}
