package localapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import localapi.mixin.StatsCounterAccessor;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatsCounter;

import java.util.TreeSet;

/** Request-local views of vanilla client data; no additional cache or history. */
final class ProgressData {
    private ProgressData() {}

    static JsonObject advancements(ClientAdvancements client) {
        JsonArray entries = new JsonArray();
        // Removed nodes may still have progress-map entries. The current tree is authoritative.
        client.tree().nodes().stream().sorted(java.util.Comparator.comparing(n -> n.holder().id().toString()))
                .forEach(node -> {
                    var holder = node.holder();
                    var advancement = holder.value();
                    JsonObject entry = new JsonObject();
                    entry.addProperty("id", holder.id().toString());
                    advancement.parent().ifPresent(parent -> entry.addProperty("parent", parent.toString()));
                    advancement.display().ifPresent(display -> {
                        entry.addProperty("icon", ItemView.holderId(display.icon().item()));
                        entry.addProperty("title", display.title().getString());
                        entry.addProperty("description", display.description().getString());
                    });
                    JsonArray requirements = new JsonArray();
                    advancement.requirements().requirements().forEach(group -> {
                        JsonArray alternatives = new JsonArray();
                        group.forEach(alternatives::add);
                        requirements.add(alternatives);
                    });
                    entry.add("requirements", requirements);
                    AdvancementProgress progress = client.progress().get(holder);
                    entry.addProperty("progress_known", progress != null);
                    if (progress != null) {
                        entry.addProperty("done", progress.isDone());
                        entry.addProperty("progress", progress.getPercent());
                        JsonObject criteria = new JsonObject();
                        JsonObject criterionNames = new JsonObject();
                        // Use the received progress, not server-only trigger definitions or local defaults.
                        TreeSet<String> names = new TreeSet<>();
                        progress.getCompletedCriteria().forEach(names::add);
                        progress.getRemainingCriteria().forEach(names::add);
                        for (String name : names) {
                            var criterion = progress.getCriterion(name);
                            if (criterion != null) {
                                criteria.addProperty(name, criterion.isDone());
                                String displayName = ClientNames.criterion(name);
                                if (displayName != null) criterionNames.addProperty(name, displayName);
                            }
                        }
                        entry.add("criteria", criteria);
                        entry.add("criterion_names", criterionNames);
                    }
                    entries.add(entry);
                });
        JsonObject out = new JsonObject();
        out.add("advancements", entries);
        return out;
    }

    static JsonObject statistics(StatsCounter counter) {
        JsonArray entries = new JsonArray();
        // getValue returns zero for absent stats. Enumerate only the actual client map instead.
        ((StatsCounterAccessor) counter).localapi$stats().object2IntEntrySet().stream()
                .sorted(java.util.Comparator.comparing(e -> e.getKey().getName()))
                .forEach(value -> {
                    Stat<?> stat = value.getKey();
                    JsonObject entry = new JsonObject();
                    entry.addProperty("type", BuiltInRegistries.STAT_TYPE.getKey(stat.getType()).toString());
                    entry.addProperty("id", statId(stat));
                    entry.addProperty("value", value.getIntValue());
                    entry.addProperty("name", ClientNames.stat(stat));
                    entry.addProperty("formatted_value", stat.format(value.getIntValue()));
                    entries.add(entry);
                });
        JsonObject out = new JsonObject();
        out.add("stats", entries);
        return out;
    }

    private static <T> String statId(Stat<T> stat) {
        return stat.getType().getRegistry().getKey(stat.getValue()).toString();
    }
}
