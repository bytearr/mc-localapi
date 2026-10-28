package localapi;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;

final class ClientNames {
    static String biome(String id) {
        if (id == null) return null;
        return Component.translatable(Identifier.parse(id).toLanguageKey("biome")).getString();
    }
    static String criterion(String value) {
        // Ordinary trigger names are not resource IDs. Never guess a minecraft: prefix.
        if (!value.contains(":")) return null;
        Identifier id = Identifier.tryParse(value);
        if (id == null) return null;
        var mc = Minecraft.getInstance();
        if (mc != null && mc.level != null && mc.level.registryAccess().lookupOrThrow(Registries.BIOME).containsKey(id)) return biome(value);
        if (BuiltInRegistries.ITEM.containsKey(id)) return BuiltInRegistries.ITEM.getValue(id).getDefaultInstance().getHoverName().getString();
        if (BuiltInRegistries.BLOCK.containsKey(id)) return BuiltInRegistries.BLOCK.getValue(id).getName().getString();
        if (BuiltInRegistries.ENTITY_TYPE.containsKey(id)) return BuiltInRegistries.ENTITY_TYPE.getValue(id).getDescription().getString();
        return null;
    }
    static String stat(Stat<?> stat) {
        Object value = stat.getValue();
        if (value instanceof net.minecraft.world.item.Item item) return item.getDefaultInstance().getHoverName().getString();
        if (value instanceof net.minecraft.world.level.block.Block block) return block.getName().getString();
        if (value instanceof net.minecraft.world.entity.EntityType<?> entity) return entity.getDescription().getString();
        if (stat.getType() == Stats.CUSTOM && value instanceof Identifier id) return Component.translatable("stat." + id.toString().replace(':', '.')).getString();
        return stat.getType().getDisplayName().getString();
    }
}
