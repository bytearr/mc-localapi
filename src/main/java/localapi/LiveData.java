package localapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.numbers.StyledFormat;
import localapi.mixin.BossHealthOverlayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Scoreboard;

import java.util.Locale;

/** Live reads only, on the game thread. No retained state or event history. */
final class LiveData {
    private LiveData() {}

    static void player(JsonObject out, LocalPlayer player) {
        out.addProperty("air", player.getAirSupply());
        out.addProperty("max_air", player.getMaxAirSupply());
        out.addProperty("on_fire", player.isOnFire());
        // The burning flag is synchronized; a reliable server-side remaining timer is not.
        out.add("fire_ticks", JsonNull.INSTANCE);
        out.addProperty("xp_progress", player.experienceProgress);
        out.addProperty("xp_total", player.totalExperience);
        JsonArray effects = new JsonArray();
        player.getActiveEffects().forEach(effect -> effects.add(ItemView.effect(effect)));
        out.add("effects", effects);
    }

    static void world(JsonObject out, ClientLevel level) {
        out.addProperty("game_time_ticks", level.getGameTime());
        var clock = level.dimensionType().defaultClock();
        if (clock.isEmpty()) {
            out.add("clock", JsonNull.INSTANCE);
            out.add("clock_time_ticks", JsonNull.INSTANCE);
            out.add("time_of_day_ticks", JsonNull.INSTANCE);
        } else {
            out.addProperty("clock", ItemView.holderId(clock.get()));
            long ticks = level.getDefaultClockTime();
            out.addProperty("clock_time_ticks", ticks);
            out.addProperty("time_of_day_ticks", Math.floorMod(ticks, 24000));
        }
    }

    static LevelChunk loadedChunk(ClientLevel level, int x, int z) {
        // false is essential: never accept ClientChunkCache's empty fallback chunk.
        return level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
    }

    static JsonObject environment(ClientLevel level, Request request) {
        BlockPos pos = new BlockPos(request.reqInt("x"), request.reqInt("y"), request.reqInt("z"));
        JsonObject out = position(pos);
        LevelChunk chunk = loadedChunk(level, pos.getX(), pos.getZ());
        out.addProperty("loaded", chunk != null);
        if (chunk == null) {
            out.addProperty("reason", "chunk_not_loaded");
            out.add("biome", JsonNull.INSTANCE);
            out.add("biome_name", JsonNull.INSTANCE);
            out.add("sky_light", JsonNull.INSTANCE);
            out.add("block_light", JsonNull.INSTANCE);
            return out;
        }
        if (level.isOutsideBuildHeight(pos)) throw new HttpError(400, "y is outside the build height");
        // Read the stored quart biome directly: biome blending can sample unloaded neighbours.
        out.addProperty("biome", ItemView.holderId(chunk.getNoiseBiome(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2)));
        out.addProperty("biome_name", ClientNames.biome(out.get("biome").isJsonNull() ? null : out.get("biome").getAsString()));
        out.addProperty("biome_sampling", "quart");
        out.addProperty("sky_light", level.getBrightness(LightLayer.SKY, pos));
        out.addProperty("block_light", level.getBrightness(LightLayer.BLOCK, pos));
        return out;
    }

    static JsonObject block(ClientLevel level, BlockPos pos) {
        LevelChunk chunk = loadedChunk(level, pos.getX(), pos.getZ());
        JsonObject out = chunk == null ? position(pos) : Json.block(chunk.getBlockState(pos), pos.getX(), pos.getY(), pos.getZ());
        out.addProperty("loaded", chunk != null);
        if (chunk == null) {
            out.add("id", JsonNull.INSTANCE);
            out.add("state", JsonNull.INSTANCE);
            out.addProperty("reason", "chunk_not_loaded");
        }
        return out;
    }

    private static JsonElement text(Component value, HolderLookup.Provider registries) {
        return ComponentSerialization.CODEC.encodeStart(registries.createSerializationContext(JsonOps.INSTANCE), value)
                .result().orElse(JsonNull.INSTANCE);
    }

    static JsonObject position(BlockPos pos) {
        JsonObject out = new JsonObject();
        out.addProperty("x", pos.getX());
        out.addProperty("y", pos.getY());
        out.addProperty("z", pos.getZ());
        return out;
    }

    static JsonObject menu(AbstractContainerMenu menu, ClientLevel level, ItemView items) {
        JsonObject out = new JsonObject();
        out.addProperty("container_id", menu.containerId);
        out.addProperty("supported", true);
        if (menu instanceof AbstractFurnaceMenu furnace) {
            out.addProperty("kind", "furnace");
            out.addProperty("lit", furnace.isLit());
            out.addProperty("cooking_progress", furnace.getBurnProgress());
            out.addProperty("fuel_progress", furnace.getLitProgress());
        } else if (menu instanceof MerchantMenu merchant) {
            out.addProperty("kind", "merchant");
            out.addProperty("level", merchant.getTraderLevel());
            out.addProperty("xp", merchant.getTraderXp());
            out.addProperty("can_restock", merchant.canRestock());
            out.addProperty("show_progress", merchant.showProgressBar());
            JsonArray offers = new JsonArray();
            int index = 0;
            for (var offer : merchant.getOffers()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("index", index++);
                entry.add("cost_a", items.item(offer.getCostA()));
                entry.add("cost_b", items.item(offer.getCostB()));
                entry.add("result", items.item(offer.getResult()));
                entry.addProperty("uses", offer.getUses());
                entry.addProperty("max_uses", offer.getMaxUses());
                entry.addProperty("out_of_stock", offer.isOutOfStock());
                entry.addProperty("xp", offer.getXp());
                offers.add(entry);
            }
            out.add("offers", offers);
        } else if (menu instanceof AnvilMenu anvil) {
            out.addProperty("kind", "anvil");
            out.addProperty("level_cost", anvil.getCost());
        } else if (menu instanceof EnchantmentMenu enchanting) {
            out.addProperty("kind", "enchanting");
            JsonArray options = new JsonArray();
            var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            for (int i = 0; i < enchanting.costs.length; i++) {
                JsonObject entry = new JsonObject();
                entry.addProperty("index", i);
                entry.addProperty("level_cost", enchanting.costs[i]);
                entry.addProperty("available", enchanting.costs[i] > 0);
                int clue = enchanting.enchantClue[i];
                entry.addProperty("enchantment", clue < 0 ? null : registry.get(clue).map(ItemView::holderId).orElse(null));
                if (enchanting.levelClue[i] < 0) entry.add("enchantment_level", JsonNull.INSTANCE);
                else entry.addProperty("enchantment_level", enchanting.levelClue[i]);
                options.add(entry);
            }
            out.add("options", options);
        } else {
            out.addProperty("kind", "unsupported");
            out.addProperty("supported", false);
        }
        return out;
    }

    static JsonObject scoreboard(Scoreboard scoreboard, HolderLookup.Provider registries) {
        JsonObject out = new JsonObject();
        JsonObject displays = new JsonObject();
        for (DisplaySlot slot : DisplaySlot.values()) {
            var objective = scoreboard.getDisplayObjective(slot);
            displays.addProperty(slot.getSerializedName(), objective == null ? null : objective.getName());
        }
        out.add("display_slots", displays);
        JsonArray objectives = new JsonArray();
        for (var objective : scoreboard.getObjectives()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", objective.getName());
            entry.addProperty("name", objective.getDisplayName().getString());
            entry.add("name_component", text(objective.getDisplayName(), registries));
            entry.addProperty("render_type", objective.getRenderType().name().toLowerCase(Locale.ROOT));
            JsonArray scores = new JsonArray();
            for (var score : scoreboard.listPlayerScores(objective)) {
                JsonObject s = new JsonObject();
                s.addProperty("owner", score.owner());
                s.addProperty("value", score.value());
                s.addProperty("name", score.ownerName().getString());
                s.add("name_component", text(score.ownerName(), registries));
                var formatted = score.formatValue(objective.numberFormatOrDefault(StyledFormat.NO_STYLE));
                s.addProperty("formatted_value", formatted.getString());
                s.add("formatted_value_component", text(formatted, registries));
                s.addProperty("hidden", score.isHidden());
                scores.add(s);
            }
            entry.add("scores", scores);
            objectives.add(entry);
        }
        out.add("objectives", objectives);
        JsonArray teams = new JsonArray();
        for (var team : scoreboard.getPlayerTeams()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", team.getName());
            entry.addProperty("name", team.getDisplayName().getString());
            entry.add("name_component", text(team.getDisplayName(), registries));
            entry.add("prefix_component", text(team.getPlayerPrefix(), registries));
            entry.add("suffix_component", text(team.getPlayerSuffix(), registries));
            entry.addProperty("prefix", team.getPlayerPrefix().getString());
            entry.addProperty("suffix", team.getPlayerSuffix().getString());
            entry.addProperty("color", team.getColor().map(color -> color.getSerializedName()).orElse(null));
            entry.addProperty("friendly_fire", team.isAllowFriendlyFire());
            entry.addProperty("see_friendly_invisibles", team.canSeeFriendlyInvisibles());
            JsonArray members = new JsonArray();
            team.getPlayers().forEach(members::add);
            entry.add("members", members);
            teams.add(entry);
        }
        out.add("teams", teams);
        return out;
    }

    static JsonArray bossbars(Minecraft minecraft) {
        JsonArray out = new JsonArray();
        var overlay = (BossHealthOverlayAccessor) minecraft.gui.hud.getBossOverlay();
        for (var boss : overlay.localapi$events().values()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", boss.getId().toString());
            entry.addProperty("name", boss.getName().getString());
            entry.add("name_component", text(boss.getName(), minecraft.level.registryAccess()));
            entry.addProperty("progress", boss.getProgress());
            entry.addProperty("color", boss.getColor().getName());
            entry.addProperty("overlay", boss.getOverlay().getName());
            entry.addProperty("darken_screen", boss.shouldDarkenScreen());
            entry.addProperty("play_music", boss.shouldPlayBossMusic());
            entry.addProperty("create_fog", boss.shouldCreateWorldFog());
            out.add(entry);
        }
        return out;
    }
}
