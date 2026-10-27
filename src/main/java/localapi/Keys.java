package localapi;

import localapi.mixin.KeyMappingAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Virtual key state on top of vanilla {@link KeyMapping}s. Game thread only.
 *
 * hold: down() keeps the key pressed until up(). It is re-applied at the start of every
 *       client tick, because vanilla resets key state when a screen opens/closes.
 * tap:  tap() presses for N client ticks (default 1), then releases. One click is registered,
 *       so attack/use/drop/swap that react to clicks fire exactly once.
 */
public final class Keys {
    private static final class State {
        final KeyMapping mapping;
        boolean held;
        int tapTicks;
        State(KeyMapping m) { mapping = m; }
    }

    private static final Map<String, State> STATES = new LinkedHashMap<>();

    private Keys() {}

    public static KeyMapping resolve(String name) {
        Options o = Minecraft.getInstance().options;
        String n = name.toLowerCase();
        KeyMapping m = switch (n) {
            case "attack", "left_click" -> o.keyAttack;
            case "use", "right_click" -> o.keyUse;
            case "sneak", "shift", "crouch" -> o.keyShift;
            case "jump" -> o.keyJump;
            case "sprint" -> o.keySprint;
            case "forward", "up" -> o.keyUp;
            case "back", "backward", "down" -> o.keyDown;
            case "left" -> o.keyLeft;
            case "right" -> o.keyRight;
            case "drop" -> o.keyDrop;
            case "swap", "swap_hands", "swap_offhand", "offhand" -> o.keySwapOffhand;
            case "inventory" -> o.keyInventory;
            case "pick", "pick_item" -> o.keyPickItem;
            case "chat" -> o.keyChat;
            case "command" -> o.keyCommand;
            case "playerlist" -> o.keyPlayerList;
            default -> null;
        };
        if (m == null && n.startsWith("hotbar")) {
            try {
                int i = Integer.parseInt(n.substring(6));
                if (i >= 1 && i <= o.keyHotbarSlots.length) m = o.keyHotbarSlots[i - 1];
            } catch (NumberFormatException ignored) {}
        }
        if (m == null) m = KeyMapping.get(name); // e.g. "key.inventory", or any mod keybind id
        if (m == null) throw new HttpError(400, "unknown key '" + name + "' (see GET /keys)");
        return m;
    }

    public static void down(String name) {
        KeyMapping m = resolve(name);
        State s = STATES.computeIfAbsent(m.getName(), k -> new State(m));
        s.held = true;
        s.tapTicks = 0;
        if (!m.isDown()) press(m, true);
    }

    public static void up(String name) {
        KeyMapping m = resolve(name);
        State s = STATES.remove(m.getName());
        if (s != null || m.isDown()) release(m);
    }

    public static void tap(String name, int ticks) {
        KeyMapping m = resolve(name);
        State s = STATES.computeIfAbsent(m.getName(), k -> new State(m));
        if (s.held) { // already held: a tap is one extra click
            ((KeyMappingAccessor) m).localapi$setClickCount(((KeyMappingAccessor) m).localapi$getClickCount() + 1);
            return;
        }
        s.tapTicks = Math.max(1, Math.min(ticks, 20 * 60));
        press(m, true);
    }

    public static void releaseAll() {
        for (State s : new ArrayList<>(STATES.values())) release(s.mapping);
        STATES.clear();
    }

    public static List<String> heldNames() {
        List<String> l = new ArrayList<>();
        for (State s : STATES.values()) if (s.held || s.tapTicks > 0) l.add(s.mapping.getName());
        return l;
    }

    public static boolean isHeld(KeyMapping m) {
        State s = STATES.get(m.getName());
        return s != null && s.held;
    }

    /** START_CLIENT_TICK: vanilla may have cleared our keys, put them back before input is read. */
    public static void onStartTick(Minecraft mc) {
        for (State s : STATES.values()) {
            if ((s.held || s.tapTicks > 0) && !s.mapping.isDown()) press(s.mapping, false);
        }
    }

    /** END_CLIENT_TICK: count down taps. */
    public static void onEndTick(Minecraft mc) {
        Iterator<State> it = STATES.values().iterator();
        while (it.hasNext()) {
            State s = it.next();
            if (s.tapTicks > 0 && --s.tapTicks == 0 && !s.held) {
                release(s.mapping);
                it.remove();
            }
        }
    }

    private static void press(KeyMapping m, boolean click) {
        m.setDown(true);
        if (click) {
            KeyMappingAccessor a = (KeyMappingAccessor) m;
            a.localapi$setClickCount(a.localapi$getClickCount() + 1);
        }
    }

    private static void release(KeyMapping m) {
        m.setDown(false);
        if (m.isDown()) { // toggle-type mapping: the second "press" toggles it back off
            m.setDown(true);
            m.setDown(false);
        }
        ((KeyMappingAccessor) m).localapi$setClickCount(0); // drop unconsumed clicks
    }
}
