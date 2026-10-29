package targethud;

import localapi.api.LocalApiPlugin;
import localapi.api.PluginContext;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

public final class TargetHudPlugin implements LocalApiPlugin {
    @Override
    public void register(PluginContext context) throws Exception {
        // Resolve read-only access before registering, so incompatible clients fail plugin loading clearly.
        var hud = new TargetHud(new MiningProgress());
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("localapi", "target_hud"), hud);
    }
}
