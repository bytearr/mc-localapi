package localapi.mixin;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.MouseSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(MouseSettingsScreen.class)
public interface MouseSettingsScreenInvoker {
    @Invoker("options") static OptionInstance<?>[] localapi$options(Options options) { throw new AssertionError(); }
}
