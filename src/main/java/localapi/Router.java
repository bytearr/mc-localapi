package localapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import localapi.mixin.ContainerScreenAccessor;
import localapi.mixin.KeyMappingAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** All routes. Everything here runs on the game thread. */
public final class Router {
    @FunctionalInterface
    interface Handler { Object handle(Request r) throws Exception; }

    private record Route(boolean mutating, Handler h) {}

    private static final Map<String, Route> ROUTES = new LinkedHashMap<>();

    private static void get(String path, Handler h) { ROUTES.put(path, new Route(false, h)); }
    private static void post(String path, Handler h) { ROUTES.put(path, new Route(true, h)); }

    static {
        // ---- read ----
        get("/state", Router::state);
        get("/world", Router::world);
        get("/player", Router::player);
        get("/target", Router::target);
        get("/hand", Router::hand);
        get("/inventory", Router::inventory);
        get("/container", Router::container);
        get("/screen", Router::screen);
        get("/entities", Router::entities);
        get("/players", Router::players);
        get("/block", Router::block);
        get("/blocks", Router::blocks);
        get("/environment", r -> LiveData.environment(needLevel(), r));
        get("/scoreboard", r -> LiveData.scoreboard(needLevel().getScoreboard(), mc().level.registryAccess()));
        get("/bossbars", r -> { needPlayer(); return LiveData.bossbars(mc()); });
        get("/advancements", r -> ProgressData.advancements(needPlayer().connection.getAdvancements()));
        get("/stats", r -> ProgressData.statistics(needPlayer().getStats()));
        get("/keys", Router::keys);
        get("/chat/log", Router::chatLog);
        get("/routes", r -> {
            JsonArray a = new JsonArray();
            ROUTES.forEach((k, v) -> a.add((v.mutating ? "POST " : "GET  ") + k));
            return a;
        });
        // ---- act ----
        post("/key/down", r -> { Keys.down(r.reqStr("key")); return heldJson(); });
        post("/key/up", r -> { Keys.up(r.reqStr("key")); return heldJson(); });
        post("/key/tap", Router::keyTap);
        post("/key/release_all", r -> { Keys.releaseAll(); return heldJson(); });
        post("/hotbar", Router::hotbar);
        post("/player/look", Router::look);
        post("/player/look_at", Router::lookAt);
        post("/container/click", Router::containerClick);
        post("/screen/close", Router::screenClose);
        post("/chat", Router::chatSend);
        post("/chat/echo", r -> {
            Minecraft mc = Minecraft.getInstance();
            mc.gui.chatListener().handleSystemMessage(Component.literal(r.reqStr("text")), false);
            return ok();
        });
        post("/screenshot", Router::screenshot);
        post("/batch", r -> BatchRequests.dispatch(r, Router::dispatch));
    }

    private Router() {}

    public static String normalize(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    public static boolean isCore(String path) { return ROUTES.containsKey(normalize(path)); }

    public static java.util.Set<String> paths() { return java.util.Collections.unmodifiableSet(ROUTES.keySet()); }

    public record RouteDoc(String method, String path, String summary, List<String> params) {}

    private static final Map<String, RouteDoc> DOCS = new LinkedHashMap<>();

    /** Documentation shown in the in-game UI. params: "name:type" (optional) or "name:type!" (required). */
    private static void doc(String path, String summary, String... params) {
        DOCS.put(path, new RouteDoc(ROUTES.get(path).mutating ? "POST" : "GET", path, summary, List.of(params)));
    }

    public static List<RouteDoc> docs() { return new ArrayList<>(DOCS.values()); }

    static {
        doc("/state", "Overview: world, pause, focus, fps, screen, held keys");
        doc("/world", "Dimension, weather, clock, height range, entity count, server");
        doc("/player", "Position, rotation, health, food, effects, air, xp, states, hands", "item_details:boolean");
        doc("/target", "Block or entity under the crosshair");
        doc("/hand", "Main hand, off hand, selected hotbar slot", "item_details:boolean");
        doc("/inventory", "Player inventory (always available)", "item_details:boolean");
        doc("/container", "The menu that is really open, all slots", "item_details:boolean", "menu_details:boolean");
        doc("/screen", "Open screen: class, title, widgets", "item_details:boolean", "menu_details:boolean");
        doc("/entities", "Entities by distance", "radius:number", "type:string", "limit:integer", "include_self:boolean");
        doc("/players", "Tab list");
        doc("/block", "Block and block state at a position", "x:integer!", "y:integer!", "z:integer!");
        doc("/blocks", "Region of blocks (max 32768)", "x1:integer!", "y1:integer!", "z1:integer!",
                "x2:integer!", "y2:integer!", "z2:integer!", "include_air:boolean");
        doc("/environment", "Live biome and lighting (loaded chunks only)", "x:integer!", "y:integer!", "z:integer!");
        doc("/scoreboard", "Client-known objectives, scores and teams");
        doc("/bossbars", "Active client boss bars");
        doc("/advancements", "Client-known advancements and received criterion progress");
        doc("/stats", "Counters currently present in the client statistics map");
        doc("/keys", "All keybinds and their state");
        doc("/chat/log", "Received chat and system messages", "since:integer", "limit:integer");
        doc("/routes", "Plain list of core routes");
        doc("/key/down", "Hold a key until /key/up", "key:string!");
        doc("/key/up", "Release a key", "key:string!");
        doc("/key/tap", "Press for N ticks, one click", "key:string!", "ticks:integer", "ms:integer");
        doc("/key/release_all", "Release everything the API holds");
        doc("/hotbar", "Select hotbar slot 0-8", "slot:integer!");
        doc("/player/look", "Set yaw / pitch", "yaw:number", "pitch:number", "relative:boolean");
        doc("/player/look_at", "Look at a point", "x:number!", "y:number!", "z:number!");
        doc("/container/click", "Click a slot of the open menu", "slot:integer!", "button:integer",
                "type:string (PICKUP QUICK_MOVE SWAP CLONE THROW QUICK_CRAFT PICKUP_ALL)", "container_id:integer");
        doc("/screen/close", "Close the open container or screen");
        doc("/chat", "Send a chat message or a /command", "text:string!");
        doc("/chat/echo", "Show a local-only message", "text:string!");
        doc("/screenshot", "Save a PNG of the game view", "base64:boolean");
        doc("/batch", "Several calls in one game step", "requests:json!");
    }

    public static Object dispatch(Request req) throws Exception {
        Route route = ROUTES.get(normalize(req.path()));
        if (route == null) {
            JsonObject extra = new JsonObject();
            JsonArray a = new JsonArray();
            localapi.plugin.PluginManager.allPaths().forEach(a::add);
            extra.add("paths", a);
            throw new HttpError(404, "no such path: " + req.path() + ". These paths exist (see 'paths').", extra);
        }
        if (route.mutating && !req.method().equals("POST")) throw new HttpError(405, req.path() + " needs POST");
        return route.h.handle(req);
    }

    // ------------------------------------------------------------------ helpers

    private static Minecraft mc() { return Minecraft.getInstance(); }

    private static LocalPlayer needPlayer() {
        LocalPlayer p = mc().player;
        if (p == null || mc().level == null) throw new HttpError(409, "not in a world");
        return p;
    }

    private static ClientLevel needLevel() {
        needPlayer();
        return mc().level;
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("done", true);
        return o;
    }

    private static JsonObject heldJson() {
        JsonObject o = new JsonObject();
        JsonArray a = new JsonArray();
        Keys.heldNames().forEach(a::add);
        o.add("held", a);
        return o;
    }

    private static String id(net.minecraft.resources.Identifier i) { return i == null ? null : i.toString(); }

    // ------------------------------------------------------------------ read routes

    private static Object state(Request r) {
        Minecraft mc = mc();
        JsonObject o = new JsonObject();
        o.addProperty("in_world", mc.player != null && mc.level != null);
        o.addProperty("singleplayer", mc.hasSingleplayerServer());
        o.addProperty("paused", mc.isPaused());
        o.addProperty("window_focused", mc.getWindow().isFocused());
        o.addProperty("fps", mc.getFps());
        Screen s = mc.gui.screen();
        o.addProperty("screen", s == null ? null : s.getClass().getName());
        o.add("held_keys", heldJson().get("held"));
        o.addProperty("api_queue_pending", GameQueue.pending());
        if (mc.player != null) {
            o.addProperty("player", mc.player.getScoreboardName());
            o.addProperty("x", mc.player.getX());
            o.addProperty("y", mc.player.getY());
            o.addProperty("z", mc.player.getZ());
        }
        return o;
    }

    private static Object world(Request r) {
        ClientLevel l = needLevel();
        JsonObject o = new JsonObject();
        o.addProperty("dimension", id(l.dimension().identifier()));
        o.addProperty("raining", l.isRaining());
        o.addProperty("thundering", l.isThundering());
        o.addProperty("min_y", l.getMinY());
        o.addProperty("max_y", l.getMaxY());
        o.addProperty("entity_count", l.getEntityCount());
        LiveData.world(o, l);
        var srv = mc().getCurrentServer();
        if (srv != null) o.addProperty("server", srv.ip);
        return o;
    }

    private static Object player(Request r) {
        LocalPlayer p = needPlayer();
        ItemView items = new ItemView(r.bool("item_details", false));
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getScoreboardName());
        o.addProperty("uuid", p.getUUID().toString());
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        o.addProperty("yaw", p.getYRot());
        o.addProperty("pitch", p.getXRot());
        Vec3 v = p.getDeltaMovement();
        o.add("velocity", Json.vec(v.x, v.y, v.z));
        o.addProperty("health", p.getHealth());
        LiveData.player(o, p);
        o.addProperty("max_health", p.getMaxHealth());
        o.addProperty("armor", p.getArmorValue());
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("saturation", p.getFoodData().getSaturationLevel());
        o.addProperty("xp_level", p.experienceLevel);
        o.addProperty("on_ground", p.onGround());
        o.addProperty("sprinting", p.isSprinting());
        o.addProperty("sneaking", p.isShiftKeyDown());
        o.addProperty("swimming", p.isSwimming());
        o.addProperty("selected_slot", p.getInventory().getSelectedSlot());
        if (mc().gameMode != null) o.addProperty("gamemode", mc().gameMode.getPlayerMode().getName());
        o.addProperty("dimension", id(mc().level.dimension().identifier()));
        o.add("main_hand", items.item(p.getMainHandItem()));
        o.add("off_hand", items.item(p.getOffhandItem()));
        return o;
    }

    private static Object target(Request r) {
        needPlayer();
        HitResult h = mc().hitResult;
        JsonObject o = new JsonObject();
        if (h == null || h.getType() == HitResult.Type.MISS) { o.addProperty("type", "miss"); return o; }
        if (h instanceof BlockHitResult b) {
            BlockPos bp = b.getBlockPos();
            o.addProperty("type", "block");
            o.add("block", LiveData.block(mc().level, bp));
            o.addProperty("face", b.getDirection().getName());
        } else if (h instanceof EntityHitResult e) {
            o.addProperty("type", "entity");
            o.add("entity", Json.entity(e.getEntity(), mc().player));
        }
        return o;
    }

    private static Object hand(Request r) {
        LocalPlayer p = needPlayer();
        ItemView items = new ItemView(r.bool("item_details", false));
        JsonObject o = new JsonObject();
        o.addProperty("selected_slot", p.getInventory().getSelectedSlot());
        o.add("main_hand", items.item(p.getMainHandItem()));
        o.add("off_hand", items.item(p.getOffhandItem()));
        return o;
    }

    /** Player inventory, always available (needs no open screen). Slot numbers are InventoryMenu slots. */
    private static Object inventory(Request r) {
        LocalPlayer p = needPlayer();
        ItemView items = new ItemView(r.bool("item_details", false));
        JsonObject o = new JsonObject();
        o.addProperty("selected_slot", p.getInventory().getSelectedSlot());
        o.addProperty("menu_id", p.inventoryMenu.containerId);
        o.add("carried", items.item(p.containerMenu.getCarried()));
        JsonArray arr = new JsonArray();
        for (Slot s : p.inventoryMenu.slots) arr.add(slotJson(s, null, items));
        o.add("slots", arr);
        o.addProperty("layout", "menu slots: 0 craft result, 1-4 craft grid, 5-8 armor (head..feet), "
                + "9-35 main inventory, 36-44 hotbar, 45 offhand");
        return o;
    }

    private static JsonObject slotJson(Slot s, Screen screen, ItemView items) {
        JsonObject o = new JsonObject();
        o.addProperty("index", s.index);
        o.addProperty("container_slot", s.getContainerSlot());
        o.addProperty("player_inventory", s.container instanceof Inventory);
        o.addProperty("active", s.isActive());
        o.addProperty("x", s.x);
        o.addProperty("y", s.y);
        if (screen instanceof AbstractContainerScreen<?> cs) {
            ContainerScreenAccessor a = (ContainerScreenAccessor) cs;
            o.addProperty("screen_x", a.localapi$leftPos() + s.x);
            o.addProperty("screen_y", a.localapi$topPos() + s.y);
        }
        o.add("item", items.item(s.getItem()));
        return o;
    }

    /** The menu the player really has open (player.containerMenu): chest, furnace, villager, inventory, ... */
    private static Object container(Request r) {
        LocalPlayer p = needPlayer();
        ItemView items = new ItemView(r.bool("item_details", false));
        AbstractContainerMenu menu = p.containerMenu;
        Screen scr = mc().gui.screen();
        boolean external = menu != p.inventoryMenu;
        JsonObject o = new JsonObject();
        o.addProperty("external", external);
        o.addProperty("container_id", menu.containerId);
        o.addProperty("state_id", menu.getStateId());
        String type = "minecraft:inventory";
        if (external) {
            try { type = id(BuiltInRegistries.MENU.getKey(menu.getType())); }
            catch (Exception e) { type = menu.getClass().getName(); }
        }
        o.addProperty("type", type);
        o.addProperty("menu_class", menu.getClass().getName());
        if (scr != null) {
            o.addProperty("screen", scr.getClass().getName());
            o.addProperty("title", scr.getTitle().getString());
        }
        o.add("carried", items.item(menu.getCarried()));
        JsonArray arr = new JsonArray();
        int ext = 0;
        for (Slot s : menu.slots) {
            arr.add(slotJson(s, scr, items));
            if (!(s.container instanceof Inventory)) ext++;
        }
        o.addProperty("slot_count", menu.slots.size());
        o.addProperty("external_slot_count", ext);
        if (r.bool("menu_details", false)) o.add("details", LiveData.menu(menu, mc().level, items));
        o.add("slots", arr);
        return o;
    }

    private static Object screen(Request r) {
        Minecraft mc = mc();
        Screen s = mc.gui.screen();
        JsonObject o = new JsonObject();
        o.addProperty("open", s != null);
        if (s == null) return o;
        o.addProperty("class", s.getClass().getName());
        o.addProperty("title", s.getTitle().getString());
        o.addProperty("is_container", s instanceof AbstractContainerScreen<?>);
        o.addProperty("width", s.width);
        o.addProperty("height", s.height);
        JsonArray w = new JsonArray();
        for (GuiEventListener c : s.children()) {
            if (c instanceof AbstractWidget aw) {
                JsonObject j = new JsonObject();
                j.addProperty("type", aw.getClass().getSimpleName());
                j.addProperty("text", aw.getMessage().getString());
                j.addProperty("x", aw.getX());
                j.addProperty("y", aw.getY());
                j.addProperty("w", aw.getWidth());
                j.addProperty("h", aw.getHeight());
                j.addProperty("active", aw.active);
                j.addProperty("visible", aw.visible);
                w.add(j);
            }
        }
        o.add("widgets", w);
        if (s instanceof AbstractContainerScreen<?>) o.add("container", (JsonElement) container(r));
        return o;
    }

    private static Object entities(Request r) {
        LocalPlayer p = needPlayer();
        double radius = Math.min(r.dbl("radius", 32), 512);
        String type = r.str("type", null);
        int limit = Math.min(r.integer("limit", 200), 2000);
        boolean self = r.bool("include_self", false);
        List<Entity> found = new ArrayList<>();
        for (Entity e : mc().level.entitiesForRendering()) {
            if (e == p && !self) continue;
            if (Math.sqrt(e.distanceToSqr(p)) > radius) continue;
            if (type != null && !BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().contains(type)) continue;
            found.add(e);
        }
        found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        JsonArray arr = new JsonArray();
        for (Entity e : found) {
            if (arr.size() >= limit) break;
            arr.add(Json.entity(e, p));
        }
        JsonObject o = new JsonObject();
        o.addProperty("count", found.size());
        o.add("entities", arr);
        return o;
    }

    private static Object players(Request r) {
        ClientPacketListener c = mc().getConnection();
        if (c == null) throw new HttpError(409, "not connected");
        JsonArray a = new JsonArray();
        for (PlayerInfo pi : c.getOnlinePlayers()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", pi.getProfile().name());
            j.addProperty("latency", pi.getLatency());
            if (pi.getGameMode() != null) j.addProperty("gamemode", pi.getGameMode().getName());
            a.add(j);
        }
        return a;
    }

    private static Object block(Request r) {
        ClientLevel l = needLevel();
        int x = r.reqInt("x"), y = r.reqInt("y"), z = r.reqInt("z");
        BlockPos bp = new BlockPos(x, y, z);
        return LiveData.block(l, bp);
    }

    private static Object blocks(Request r) {
        ClientLevel l = needLevel();
        int x1 = r.reqInt("x1"), y1 = r.reqInt("y1"), z1 = r.reqInt("z1");
        int x2 = r.reqInt("x2"), y2 = r.reqInt("y2"), z2 = r.reqInt("z2");
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.max(Math.min(y1, y2), l.getMinY()), maxY = Math.min(Math.max(y1, y2), l.getMaxY());
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
        long volume = new BlockRegion(minX, maxX, minY, maxY, minZ, maxZ).volume();
        // Preflight all needed chunks before reading any state. Never return a partial region as complete.
        if (volume > 0) {
            JsonArray missing = new JsonArray();
            for (int cx = minX >> 4; cx <= (maxX >> 4); cx++)
                for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++)
                    if (LiveData.loadedChunk(l, cx << 4, cz << 4) == null) {
                        JsonArray position = new JsonArray(); position.add(cx); position.add(cz); missing.add(position);
                    }
            if (!missing.isEmpty()) {
                JsonObject extra = new JsonObject();
                extra.addProperty("loaded", false);
                extra.add("unloaded_chunks", missing);
                throw new HttpError(409, "region contains unloaded chunks", extra);
            }
        }
        boolean air = r.bool("include_air", false);
        List<String> palette = new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        JsonArray rows = new JsonArray();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        // Long counters cannot wrap at Integer.MAX_VALUE. Empty height intersections do no work.
        for (long x = minX; volume > 0 && x <= maxX; x++)
            for (long y = minY; y <= maxY; y++)
                for (long z = minZ; z <= maxZ; z++) {
                    mp.set((int) x, (int) y, (int) z);
                    BlockState st = LiveData.loadedChunk(l, (int) x, (int) z).getBlockState(mp);
                    if (!air && st.isAir()) continue;
                    String bid = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                    int pi = index.computeIfAbsent(bid, k -> { palette.add(k); return palette.size() - 1; });
                    JsonArray row = new JsonArray();
                    row.add(x); row.add(y); row.add(z); row.add(pi);
                    rows.add(row);
                }
        JsonObject o = new JsonObject();
        JsonArray pal = new JsonArray();
        palette.forEach(pal::add);
        o.add("palette", pal);
        o.add("blocks", rows); // [x, y, z, palette_index]
        return o;
    }

    private static Object keys(Request r) {
        JsonArray a = new JsonArray();
        for (KeyMapping m : mc().options.keyMappings) {
            JsonObject j = new JsonObject();
            j.addProperty("name", m.getName());
            j.addProperty("bound_to", ((KeyMappingAccessor) m).localapi$getKey().getName());
            j.addProperty("down", m.isDown());
            j.addProperty("held_by_api", Keys.isHeld(m));
            a.add(j);
        }
        JsonObject o = new JsonObject();
        o.add("keys", a);
        o.addProperty("aliases", "attack use sneak jump sprint forward back left right drop swap_hands "
                + "inventory pick_item chat command playerlist hotbar1..hotbar9, or any name from this list");
        return o;
    }

    private static Object chatLog(Request r) {
        return ChatLog.since((long) r.dbl("since", 0), Math.min(r.integer("limit", 100), 500));
    }

    // ------------------------------------------------------------------ act routes

    private static Object keyTap(Request r) {
        needPlayerOrScreen();
        int ticks = r.has("ms") ? (int) Math.ceil(r.dbl("ms", 50) / 50.0) : r.integer("ticks", 1);
        Keys.tap(r.reqStr("key"), ticks);
        return heldJson();
    }

    private static void needPlayerOrScreen() {
        if (mc().player == null && mc().gui.screen() == null) throw new HttpError(409, "nothing to send keys to");
    }

    private static Object hotbar(Request r) {
        LocalPlayer p = needPlayer();
        int slot = r.reqInt("slot");
        if (slot < 0 || slot > 8) throw new HttpError(400, "slot must be 0-8");
        p.getInventory().setSelectedSlot(slot);
        return hand(r);
    }

    private static Object look(Request r) {
        LocalPlayer p = needPlayer();
        boolean rel = r.bool("relative", false);
        float yaw = r.has("yaw") ? (float) r.dbl("yaw", 0) + (rel ? p.getYRot() : 0) : p.getYRot();
        float pitch = r.has("pitch") ? (float) r.dbl("pitch", 0) + (rel ? p.getXRot() : 0) : p.getXRot();
        setLook(p, yaw, pitch);
        return player(r);
    }

    private static Object lookAt(Request r) {
        LocalPlayer p = needPlayer();
        Vec3 eye = p.getEyePosition();
        double dx = r.reqDbl("x") - eye.x, dy = r.reqDbl("y") - eye.y, dz = r.reqDbl("z") - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        setLook(p, yaw, pitch);
        return player(r);
    }

    private static void setLook(LocalPlayer p, float yaw, float pitch) {
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch))
            throw new HttpError(400, "yaw and pitch must be finite");
        pitch = Math.max(-90f, Math.min(90f, pitch));
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    private static Object containerClick(Request r) {
        LocalPlayer p = needPlayer();
        AbstractContainerMenu menu = p.containerMenu;
        if (r.has("container_id") && r.integer("container_id", -1) != menu.containerId)
            throw new HttpError(409, "container changed (open id is " + menu.containerId + ")");
        int slot = r.reqInt("slot");
        if (slot != -999 && (slot < 0 || slot >= menu.slots.size()))
            throw new HttpError(400, "slot out of range 0.." + (menu.slots.size() - 1) + " (or -999 = outside)");
        int button = r.integer("button", 0);
        ContainerInput type;
        try { type = ContainerInput.valueOf(r.str("type", "PICKUP").toUpperCase()); }
        catch (IllegalArgumentException e) { throw new HttpError(400, "type: PICKUP QUICK_MOVE SWAP CLONE THROW QUICK_CRAFT PICKUP_ALL"); }
        mc().gameMode.handleContainerInput(menu.containerId, slot, button, type, p);
        return container(r);
    }

    private static Object screenClose(Request r) {
        Minecraft mc = mc();
        LocalPlayer p = mc.player;
        if (p != null && p.containerMenu != p.inventoryMenu) p.closeContainer();
        else mc.gui.setScreen(null);
        return ok();
    }

    private static Object chatSend(Request r) {
        needPlayer();
        String text = r.reqStr("text");
        if (text.isEmpty() || text.length() > 256) throw new HttpError(400, "text must be 1-256 chars");
        ClientPacketListener c = mc().getConnection();
        if (c == null) throw new HttpError(409, "not connected");
        if (text.startsWith("/")) c.sendCommand(text.substring(1));
        else c.sendChat(text);
        return ok();
    }

    private static Object screenshot(Request r) {
        Minecraft mc = mc();
        boolean b64 = r.bool("base64", false);
        Path dir = FabricLoader.getInstance().getGameDir().resolve("localapi-screenshots");
        Path file = dir.resolve("shot-" + System.currentTimeMillis() + ".png");
        CompletableFuture<JsonElement> fut = new CompletableFuture<>();
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), img -> {
            // PNG encode off the game thread so the client does not stutter.
            CompletableFuture.runAsync(() -> {
                try (img) {
                    Files.createDirectories(dir);
                    img.writeToFile(file);
                    JsonObject o = new JsonObject();
                    o.addProperty("path", file.toString());
                    o.addProperty("width", img.getWidth());
                    o.addProperty("height", img.getHeight());
                    if (b64) o.addProperty("png_base64", Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
                    fut.complete(o);
                } catch (Throwable t) {
                    fut.completeExceptionally(t);
                }
            });
        });
        return fut;
    }

}
