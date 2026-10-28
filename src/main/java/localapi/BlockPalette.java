package localapi;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Request-local palette; same block ID can have different state/position-dependent map colors. */
final class BlockPalette {
    private final net.minecraft.client.color.block.BlockColors colors;
    BlockPalette() { this(net.minecraft.client.Minecraft.getInstance().getBlockColors()); }
    BlockPalette(net.minecraft.client.color.block.BlockColors colors) { this.colors = colors; }
    private record Entry(String id, int color) {}
    private final Map<Entry, Integer> index = new LinkedHashMap<>();
    int add(BlockState state, BlockGetter level, BlockPos pos) {
        Entry key = new Entry(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), color(state, level, pos));
        return index.computeIfAbsent(key, unused -> index.size());
    }
    private int color(BlockState state, BlockGetter level, BlockPos pos) {
        var sources = colors.getTintSources(state);
        if (!sources.isEmpty() && level instanceof net.minecraft.client.renderer.block.BlockAndTintGetter world) {
            // A palette has one RGB per entry: use the first active tint layer in registry order.
            for (var source : sources) {
                int tint = source.colorInWorld(state, world, pos);
                if (tint != -1) return tint & 0xffffff;
            }
            // 26.3 registers water as a particle-only tint; colorInWorld is -1 for that source.
            for (var source : sources) {
                int tint = source.colorAsTerrainParticle(state, world, pos);
                if (tint != -1) return tint & 0xffffff;
            }
        }
        return state.getMapColor(level, pos).col;
    }
    void write(JsonObject out) {
        JsonArray ids = new JsonArray(), entries = new JsonArray();
        for (Entry key : index.keySet()) {
            ids.add(key.id); JsonObject entry = new JsonObject(); entry.addProperty("id", key.id);
            entry.addProperty("map_color", key.color); entries.add(entry);
        }
        out.add("palette", ids); // Existing string-array contract remains intact.
        out.add("palette_entries", entries);
    }
}
