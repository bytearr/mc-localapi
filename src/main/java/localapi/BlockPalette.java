package localapi;

import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Request-local palette; same block ID can have different state/position-dependent map colors. */
final class BlockPalette {
    private record Entry(String id, int color) {}
    private final Map<Entry, Integer> index = new LinkedHashMap<>();
    int add(BlockState state, BlockGetter level, BlockPos pos) {
        Entry key = new Entry(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), state.getMapColor(level, pos).col);
        return index.computeIfAbsent(key, unused -> index.size());
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
