package hoverchat;

import localapi.api.LocalApiPlugin;
import localapi.api.PluginContext;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import com.mojang.blaze3d.platform.InputConstants;

import java.lang.reflect.Field;

/** Shift+T sends the hovered stack into chat. The server only accepts the text. */
public final class HoverChatPlugin implements LocalApiPlugin {
    // SDL scancodes. InputConstants.isKeyDown reads SDL_GetKeyboardState, not GLFW.
    private static final int SCAN_T = 23;
    private static final int SCAN_LSHIFT = 225;
    private static final int SCAN_RSHIFT = 229;

    private Field hoveredSlot;
    private boolean wasDown;

    @Override
    public void register(PluginContext ctx) throws Exception {
        hoveredSlot = AbstractContainerScreen.class.getDeclaredField("hoveredSlot");
        hoveredSlot.setAccessible(true);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        if (mc.player == null || mc.gui.screen() == null) {
            wasDown = false;
            return;
        }
        boolean down = InputConstants.isKeyDown(SCAN_T)
                && (InputConstants.isKeyDown(SCAN_LSHIFT) || InputConstants.isKeyDown(SCAN_RSHIFT));
        boolean edge = down && !wasDown;
        wasDown = down;
        if (!edge) return;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) return;
        if (screen.getFocused() instanceof EditBox) return;
        ItemStack stack = hovered(screen);
        if (stack.isEmpty()) stack = mc.player.containerMenu.getCarried();
        if (stack.isEmpty()) return;
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) return;
        connection.sendChat(message(stack));
    }

    /** What other players can actually read. Chat packets have no item tooltip. */
    private static String message(ItemStack stack) {
        String name = oneLine(stack.getHoverName().getString());
        if (name.isEmpty()) name = "Item";
        String head = stack.getCount() > 1 ? "[" + name + " x" + stack.getCount() + "]" : "[" + name + "]";
        ItemEnchantments enchants = stack.getEnchantments();
        if (enchants.isEmpty()) enchants = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (enchants.isEmpty()) return clip(head);
        StringBuilder parts = new StringBuilder();
        for (var entry : enchants.entrySet()) {
            String part = oneLine(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
            if (part.isEmpty()) continue;
            int extra = (parts.length() == 0 ? 1 : 2) + part.length();
            if (head.length() + parts.length() + extra > 256) break;
            if (parts.length() > 0) parts.append(", ");
            else parts.append(' ');
            parts.append(part);
        }
        return clip(head + parts);
    }

    private static String oneLine(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String clip(String text) {
        return text.length() > 256 ? text.substring(0, 256) : text;
    }

    private ItemStack hovered(AbstractContainerScreen<?> screen) {
        try {
            Object slot = hoveredSlot.get(screen);
            if (slot instanceof Slot s && s.hasItem()) return s.getItem();
        } catch (IllegalAccessException ignored) {
        }
        return ItemStack.EMPTY;
    }
}
