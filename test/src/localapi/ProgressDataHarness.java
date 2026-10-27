package localapi;

import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import localapi.mixin.StatsCounterAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.advancements.*;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.stats.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Real vanilla data objects, without starting a client/window. */
public final class ProgressDataHarness {
    private static int checks;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    // Headless equivalent of the accessor; no fake statistics implementation.
    private static final class Counter extends StatsCounter implements StatsCounterAccessor {
        public Object2IntMap<Stat<?>> localapi$stats() { return stats; }
    }

    private static JsonObject first(ClientAdvancements client) {
        return ProgressData.advancements(client).getAsJsonArray("advancements").get(0).getAsJsonObject();
    }

    private static void advancements() {
        var client = new ClientAdvancements(null, null);
        check(ProgressData.advancements(client).getAsJsonArray("advancements").isEmpty(), "unknown advancements absent");
        var requirements = new AdvancementRequirements(List.of(List.of("a", "b"), List.of("c")));
        // Network advancements have requirements, but no server-side trigger definitions.
        var holder = new AdvancementHolder(Identifier.parse("test:progress"), new Advancement(
                Optional.empty(), Optional.empty(), AdvancementRewards.EMPTY, Map.of(), requirements, false));
        client.tree().addAll(List.of(holder));
        var unknown = first(client);
        check(!unknown.get("progress_known").getAsBoolean(), "missing progress explicit");
        check(!unknown.has("done") && !unknown.has("criteria") && !unknown.has("progress"), "missing progress never becomes false or zero");
        check(unknown.getAsJsonArray("requirements").get(0).getAsJsonArray().size() == 2, "OR group preserved");
        var progress = new AdvancementProgress();
        progress.update(requirements);
        client.progress().put(holder, progress);
        var empty = first(client);
        check(!empty.get("done").getAsBoolean() && empty.get("progress").getAsFloat() == 0, "received zero progress");
        check(empty.getAsJsonObject("criteria").size() == 3, "all received criteria present despite empty trigger definitions");
        progress.grantProgress("b");
        var partial = first(client);
        check(partial.getAsJsonObject("criteria").get("b").getAsBoolean(), "criterion grant live");
        check(!partial.getAsJsonObject("criteria").get("a").getAsBoolean(), "remaining criterion false");
        check(partial.get("progress").getAsFloat() == 0.5F, "progress counts requirement groups not criteria");
        progress.grantProgress("c");
        check(first(client).get("done").getAsBoolean(), "completed AND of OR groups");
        progress.revokeProgress("b");
        check(!first(client).get("done").getAsBoolean(), "revocation live");
        check(partial.getAsJsonObject("criteria").get("b").getAsBoolean(), "returned JSON detached");
        client.tree().remove(Set.of(holder.id()));
        check(client.progress().containsKey(holder), "vanilla may retain removed progress");
        check(ProgressData.advancements(client).getAsJsonArray("advancements").isEmpty(), "removed nodes not resurrected from progress map");
        client.update(new ClientboundUpdateAdvancementsPacket(true, List.of(), Set.of(), Map.of(), false));
        check(client.progress().isEmpty() && ProgressData.advancements(client).getAsJsonArray("advancements").isEmpty(), "server reset clears view");
    }

    private static void statistics() throws Exception {
        var counter = new Counter();
        check(ProgressData.statistics(counter).getAsJsonArray("stats").isEmpty(), "no invented stats");
        var deaths = Stats.CUSTOM.get(Stats.DEATHS);
        check(counter.getValue(deaths) == 0 && ProgressData.statistics(counter).getAsJsonArray("stats").isEmpty(), "default zero is not known zero");
        counter.setValue(null, deaths, 0);
        var zero = ProgressData.statistics(counter).getAsJsonArray("stats").get(0).getAsJsonObject();
        check(zero.get("value").getAsInt() == 0 && zero.get("id").getAsString().equals("minecraft:deaths"), "explicit zero included");
        counter.setValue(null, deaths, 3);
        check(ProgressData.statistics(counter).getAsJsonArray("stats").get(0).getAsJsonObject().get("value").getAsInt() == 3, "counter updates live");
        check(zero.get("value").getAsInt() == 0, "counter response detached");
        counter.setValue(null, Stats.BLOCK_MINED.get(Blocks.STONE), 4);
        counter.setValue(null, Stats.ITEM_USED.get(Items.APPLE), 5);
        counter.setValue(null, Stats.ITEM_CRAFTED.get(Items.TORCH), 6);
        counter.setValue(null, Stats.ENTITY_KILLED.get(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:zombie"))), 7);
        counter.setValue(null, Stats.CUSTOM.get(Stats.PLAY_TIME), 1200);
        counter.setValue(null, Stats.CUSTOM.get(Stats.WALK_ONE_CM), 4321);
        var result = ProgressData.statistics(counter).getAsJsonArray("stats");
        check(result.size() == 7, "only seven known counters");
        Set<String> types = new java.util.HashSet<>();
        for (var value : result) {
            var entry = value.getAsJsonObject();
            types.add(entry.get("type").getAsString());
            if (entry.get("id").getAsString().equals("minecraft:walk_one_cm"))
                check(entry.get("value").getAsInt() == 4321, "distance stays in raw centimeters");
            if (entry.get("id").getAsString().equals("minecraft:play_time"))
                check(entry.get("value").getAsInt() == 1200, "time stays in raw ticks");
        }
        check(types.equals(Set.of("minecraft:custom", "minecraft:mined", "minecraft:used", "minecraft:crafted", "minecraft:killed")), "all requested categories identified");
        check(ProgressData.statistics(new Counter()).getAsJsonArray("stats").isEmpty(), "new connection cannot inherit API state");
        check(Object2IntMap.class.isAssignableFrom(StatsCounter.class.getDeclaredField("stats").getType()), "accessor target matches 26.3");
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        advancements();
        statistics();
        System.out.println("PASS " + checks + " advancement/statistics checks");
    }
}
