package localapi;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.*;
import java.util.*;

final class EnchantmentData {
    record Target(int slot, ItemStack stack) {}
    static void add(JsonObject out, ItemStack stack) {
        var mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) { out.add("fits", JsonNull.INSTANCE); return; }
        List<Target> targets = mc.player == null ? List.of() : mc.player.inventoryMenu.slots.stream()
                .map(slot -> new Target(slot.index, slot.getItem())).toList();
        add(out, stack, mc.level.registryAccess(), targets);
    }
    static void add(JsonObject out, ItemStack stack, HolderLookup.Provider registries, List<Target> targets) {
        var known = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        JsonArray fits = new JsonArray();
        registries.lookupOrThrow(Registries.ENCHANTMENT).listElements()
                .sorted(Comparator.comparing(ItemView::holderId)).forEach(enchantment -> {
                    int level = known.getLevel(enchantment);
                    if (level < enchantment.value().getMaxLevel() && compatible(enchantment, stack)) fits.add(entry(enchantment, level));
                });
        out.add("fits", fits);
        var stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
        if (stored != null) {
            JsonArray books = new JsonArray();
            stored.entrySet().stream().sorted(Comparator.comparing(e -> ItemView.holderId(e.getKey()))).forEach(e -> {
                JsonObject row = entry(e.getKey(), e.getIntValue()); JsonArray slots = new JsonArray();
                for (Target target : targets) if (target.slot >= net.minecraft.world.inventory.InventoryMenu.ARMOR_SLOT_START && target.slot <= net.minecraft.world.inventory.InventoryMenu.SHIELD_SLOT && !target.stack.isEmpty() && compatible(e.getKey(), target.stack)) slots.add(target.slot);
                row.add("slots", slots); books.add(row);
            });
            out.add("book_targets", books);
        }
    }
    static boolean compatible(Holder<Enchantment> enchantment, ItemStack stack) {
        if (!enchantment.value().canEnchant(stack)) return false;
        for (var existing : stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).keySet()) {
            // The same enchantment may be upgraded; areCompatible(e,e) deliberately returns false in vanilla.
            if (!existing.equals(enchantment) && !Enchantment.areCompatible(enchantment, existing)) return false;
        }
        return true;
    }
    private static JsonObject entry(Holder<Enchantment> enchantment, int level) {
        JsonObject out = new JsonObject(); out.addProperty("id", ItemView.holderId(enchantment));
        out.addProperty("name", enchantment.value().description().getString());
        out.addProperty("max_level", enchantment.value().getMaxLevel()); out.addProperty("level", level); return out;
    }
}
