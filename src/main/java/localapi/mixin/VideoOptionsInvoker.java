package localapi.mixin;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(VideoSettingsScreen.class)
public interface VideoOptionsInvoker {
    @Invoker("displayOptions") static OptionInstance<?>[] localapi$display(Options options) { throw new AssertionError(); }
    @Invoker("qualityOptions") static OptionInstance<?>[] localapi$quality(Options options) { throw new AssertionError(); }
    @Invoker("preferenceOptions") static OptionInstance<?>[] localapi$preferences(Options options) { throw new AssertionError(); }
}
