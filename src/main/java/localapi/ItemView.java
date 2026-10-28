package localapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.stream.Stream;

/** A request-local serializer. Never retains stacks or snapshots between requests. */
final class ItemView {
    static final int MAX_DETAILS = 128;
    static final int MAX_DEPTH = 2;
    private final boolean detailed;
    private int remaining = MAX_DETAILS;

    ItemView(boolean detailed) { this.detailed = detailed; }

    JsonObject item(ItemStack stack) { return item(stack, 0); }

    private JsonObject item(ItemStack stack, int depth) {
        JsonObject out = Json.item(stack); // Keep every existing field, including the empty-stack shape.
        if (!detailed || stack == null || stack.isEmpty()) return out;
        JsonObject d = new JsonObject();
        out.add("details", d);
        if (remaining == 0 || depth > MAX_DEPTH) {
            d.addProperty("available", false);
            d.addProperty("reason", remaining == 0 ? "item_limit" : "depth_limit");
            return out;
        }
        remaining--;
        out.add("tooltip", tooltip(stack));
        EnchantmentData.add(out, stack);
        d.addProperty("available", true);
        d.add("enchantments", enchantments(stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)));
        d.add("stored_enchantments", enchantments(stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY)));
        JsonArray lore = new JsonArray();
        var lines = stack.get(DataComponents.LORE);
        if (lines != null) lines.lines().forEach(line -> lore.add(line.getString()));
        d.add("lore", lore);
        JsonArray componentIds = new JsonArray();
        stack.getComponents().keySet().forEach(type -> {
            var id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if (id != null) componentIds.add(id.toString());
        });
        d.add("component_ids", componentIds);
        var potion = stack.get(DataComponents.POTION_CONTENTS);
        if (potion == null) d.add("potion", JsonNull.INSTANCE);
        else {
            JsonObject p = new JsonObject();
            p.addProperty("id", potion.potion().map(ItemView::holderId).orElse(null));
            p.addProperty("color", potion.getColor());
            p.addProperty("duration_scale", stack.getOrDefault(DataComponents.POTION_DURATION_SCALE, 1.0F));
            JsonArray effects = new JsonArray();
            potion.getAllEffects().forEach(effect -> effects.add(effect(effect)));
            p.add("effects", effects);
            d.add("potion", p);
        }
        var container = stack.get(DataComponents.CONTAINER);
        d.add("container", container == null ? JsonNull.INSTANCE : contents(container.itemCopies(), container.size(), depth));
        var bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        d.add("bundle", bundle == null ? JsonNull.INSTANCE : contents(bundle.itemCopies(), bundle.size(), depth));

        return out;
    }

    private JsonObject contents(Stream<ItemStack> stacks, int size, int depth) {
        JsonObject out = new JsonObject();
        JsonArray items = new JsonArray();
        // Include slot positions; never silently turn a truncated container into an empty one.
        int slotsRead = 0;
        try (stacks) {
            if (depth < MAX_DEPTH) {
                var it = stacks.iterator();
                while (remaining > 0 && slotsRead < MAX_DETAILS && it.hasNext()) {
                    ItemStack stack = it.next();
                    JsonObject entry = new JsonObject();
                    entry.addProperty("slot", slotsRead++);
                    entry.add("item", item(stack, depth + 1));
                    items.add(entry);
                }
            }
        }
        out.addProperty("size", size);
        out.addProperty("truncated", slotsRead < size);
        out.add("items", items);
        return out;
    }

    private static JsonArray enchantments(ItemEnchantments enchantments) {
        JsonArray out = new JsonArray();
        for (var entry : enchantments.entrySet()) {
            JsonObject e = new JsonObject();
            e.addProperty("id", holderId(entry.getKey()));
            e.addProperty("level", entry.getIntValue());
            e.addProperty("name", net.minecraft.world.item.enchantment.Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
            out.add(e);
        }
        return out;
    }

    private static JsonArray tooltip(ItemStack stack) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        var lines = mc == null ? stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.EMPTY, null, net.minecraft.world.item.TooltipFlag.NORMAL)
                : net.minecraft.client.gui.screens.Screen.getTooltipFromItem(mc, stack);
        return tooltipLines(lines);
    }

    static JsonArray tooltipLines(java.util.List<net.minecraft.network.chat.Component> lines) {
        JsonArray out = new JsonArray();
        for (var line : lines) {
            JsonObject row = new JsonObject(); row.addProperty("text", line.getString());
            var color = line.getStyle().getColor(); row.addProperty("color", color == null ? 0xffffff : color.getValue());
            JsonArray runs = new JsonArray();
            line.visit((style, text) -> {
                JsonObject run = new JsonObject(); run.addProperty("text", text);
                run.addProperty("color", style.getColor() == null ? 0xffffff : style.getColor().getValue());
                runs.add(run); return java.util.Optional.empty();
            }, net.minecraft.network.chat.Style.EMPTY);
            row.add("runs", runs); out.add(row);
        }
        return out;
    }

    static String holderId(Holder<?> holder) {
        return holder.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
    }

    static JsonObject effect(MobEffectInstance effect) {
        JsonObject out = new JsonObject();
        out.addProperty("id", holderId(effect.getEffect()));
        out.addProperty("name", effect.getEffect().value().getDisplayName().getString());
        out.addProperty("amplifier", effect.getAmplifier());
        out.addProperty("infinite", effect.isInfiniteDuration());
        if (effect.isInfiniteDuration()) out.add("duration_ticks", JsonNull.INSTANCE);
        else out.addProperty("duration_ticks", effect.getDuration());
        out.addProperty("ambient", effect.isAmbient());
        out.addProperty("visible", effect.isVisible());
        out.addProperty("show_icon", effect.showIcon());
        return out;
    }
}
