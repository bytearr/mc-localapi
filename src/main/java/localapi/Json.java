package localapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** JSON views of game objects. Game thread only. */
public final class Json {
    private Json() {}

    public static JsonObject item(ItemStack s) {
        JsonObject o = new JsonObject();
        if (s == null || s.isEmpty()) {
            o.addProperty("empty", true);
            o.addProperty("id", "minecraft:air");
            o.addProperty("count", 0);
            return o;
        }
        o.addProperty("empty", false);
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        o.addProperty("count", s.getCount());
        o.addProperty("name", s.getHoverName().getString());
        o.addProperty("max_stack", s.getMaxStackSize());
        if (s.isDamageableItem()) {
            o.addProperty("damage", s.getDamageValue());
            o.addProperty("max_damage", s.getMaxDamage());
        }
        return o;
    }

    public static JsonObject entity(Entity e, Entity from) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.getId());
        o.addProperty("uuid", e.getUUID().toString());
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        o.addProperty("name", e.getName().getString());
        o.addProperty("x", e.getX());
        o.addProperty("y", e.getY());
        o.addProperty("z", e.getZ());
        o.addProperty("yaw", e.getYRot());
        o.addProperty("pitch", e.getXRot());
        o.addProperty("on_ground", e.onGround());
        if (from != null) o.addProperty("distance", Math.sqrt(e.distanceToSqr(from)));
        if (e instanceof LivingEntity le) {
            o.addProperty("health", le.getHealth());
            o.addProperty("max_health", le.getMaxHealth());
        }
        return o;
    }

    public static JsonObject block(BlockState st, int x, int y, int z) {
        JsonObject o = new JsonObject();
        o.addProperty("x", x);
        o.addProperty("y", y);
        o.addProperty("z", z);
        o.addProperty("id", BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
        JsonObject props = new JsonObject();
        for (Property<?> p : st.getProperties()) props.addProperty(p.getName(), st.getValue(p).toString());
        o.add("state", props);
        return o;
    }

    public static JsonArray vec(double... v) {
        JsonArray a = new JsonArray();
        for (double d : v) a.add(d);
        return a;
    }
}
