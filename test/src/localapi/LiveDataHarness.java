package localapi;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.util.List;
import java.util.Optional;

/** Exercises real Minecraft 26.3 data objects without starting a client or window. */
public final class LiveDataHarness {
    private static int checks;
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        checks++;
    }

    private static void items(HolderLookup.Provider registries) {
        ItemStack stone = new ItemStack(Items.STONE, 7);
        JsonObject basic = new ItemView(false).item(stone);
        check(basic.size() == 5, "unchanged ordinary item field count");
        check(basic.get("id").getAsString().equals("minecraft:stone") && basic.get("count").getAsInt() == 7, "basic item identity");
        check(!basic.has("details"), "details absent by default");
        check(new ItemView(true).item(ItemStack.EMPTY).size() == 3, "empty stack shape preserved");
        stone.set(DataComponents.CUSTOM_NAME, Component.literal("Named stone"));
        stone.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Live lore"))));
        JsonObject detailed = new ItemView(true).item(stone);
        check(detailed.get("name").getAsString().equals("Named stone"), "current custom name");
        check(detailed.getAsJsonObject("details").getAsJsonArray("lore").get(0).getAsString().equals("Live lore"), "lore opt-in");
        check(detailed.getAsJsonObject("details").get("container").isJsonNull(), "absent container is explicit");
        check(detailed.getAsJsonObject("details").getAsJsonArray("component_ids").toString().contains("minecraft:custom_name"), "component IDs exposed");
        stone.setCount(2);
        check(new ItemView(true).item(stone).get("count").getAsInt() == 2, "next request reads new stack count");
        check(detailed.get("count").getAsInt() == 7, "response is a detached value");

        ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
        tool.setDamageValue(20);
        var efficiency = registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
        tool.enchant(efficiency, 3);
        JsonObject t = new ItemView(true).item(tool);
        check(t.get("damage").getAsInt() == 20 && t.has("max_damage"), "durability fields preserved");
        var enchantment = t.getAsJsonObject("details").getAsJsonArray("enchantments").get(0).getAsJsonObject();
        check(enchantment.get("id").getAsString().equals("minecraft:efficiency") && enchantment.get("level").getAsInt() == 3, "enchantment identity and level");
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        var stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        stored.set(efficiency, 4);
        book.set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
        check(new ItemView(true).item(book).getAsJsonObject("details").getAsJsonArray("stored_enchantments").get(0).getAsJsonObject().get("level").getAsInt() == 4, "stored book enchantments");
        ItemStack potion = PotionContents.createItemStack(Items.POTION, Potions.SWIFTNESS);
        JsonObject p = new ItemView(true).item(potion).getAsJsonObject("details").getAsJsonObject("potion");
        check(p.get("id").getAsString().equals("minecraft:swiftness"), "potion identity");
        check(!p.getAsJsonArray("effects").isEmpty(), "potion effects");

        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(ItemStack.EMPTY, stone)));
        var contents = new ItemView(true).item(box).getAsJsonObject("details").getAsJsonObject("container");
        check(contents.get("size").getAsInt() == 2 && !contents.get("truncated").getAsBoolean(), "container slot count");
        check(contents.getAsJsonArray("items").get(1).getAsJsonObject().get("slot").getAsInt() == 1, "empty slots do not renumber contents");
        box.set(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
        check(new ItemView(true).item(box).getAsJsonObject("details").getAsJsonObject("container").get("size").getAsInt() == 0, "changed contents read live");
        for (int i = 0; i < 4; i++) {
            ItemStack outer = new ItemStack(Items.SHULKER_BOX);
            outer.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(box)));
            box = outer;
        }
        JsonObject node = new ItemView(true).item(box);
        for (int depth = 0; depth < ItemView.MAX_DEPTH; depth++)
            node = node.getAsJsonObject("details").getAsJsonObject("container").getAsJsonArray("items").get(0).getAsJsonObject().getAsJsonObject("item");
        check(node.getAsJsonObject("details").getAsJsonObject("container").get("truncated").getAsBoolean(), "nested contents explicitly truncated");
        ItemView budget = new ItemView(true);
        for (int i = 0; i < ItemView.MAX_DETAILS; i++) budget.item(stone);
        var exhausted = budget.item(stone);
        check(!exhausted.getAsJsonObject("details").get("available").getAsBoolean(), "request detail budget enforced");
        check(exhausted.get("count").getAsInt() == 2, "budget does not remove basic fields");
        check(new ItemView(true).item(stone).getAsJsonObject("details").get("available").getAsBoolean(), "budget resets each request");
    }

    private static void effects() {
        var timed = ItemView.effect(new MobEffectInstance(MobEffects.SPEED, 80, 2));
        check(timed.get("duration_ticks").getAsInt() == 80 && timed.get("amplifier").getAsInt() == 2, "effect units and amplifier");
        var infinite = ItemView.effect(new MobEffectInstance(MobEffects.SPEED, -1));
        check(infinite.get("infinite").getAsBoolean() && infinite.get("duration_ticks").isJsonNull(), "infinite duration explicit");
    }

    private static void scoreboard(HolderLookup.Provider registries) {
        Scoreboard board = new Scoreboard();
        check(LiveData.scoreboard(board, registries).getAsJsonArray("objectives").isEmpty(), "empty scoreboard");
        var objective = board.addObjective("test", ObjectiveCriteria.DUMMY, Component.literal("Points").withStyle(ChatFormatting.GOLD), ObjectiveCriteria.RenderType.INTEGER, false, StyledFormat.NO_STYLE);
        board.setDisplayObjective(DisplaySlot.SIDEBAR, objective);
        var holder = ScoreHolder.forNameOnly("player");
        board.getOrCreatePlayerScore(holder, objective).set(12);
        var team = board.addPlayerTeam("blue");
        team.setColor(Optional.of(TeamColor.BLUE));
        team.setPlayerPrefix(Component.literal("[B] ").withStyle(ChatFormatting.BLUE));
        board.addPlayerToTeam("player", team);
        var first = LiveData.scoreboard(board, registries);
        check(first.getAsJsonObject("display_slots").get("sidebar").getAsString().equals("test"), "display slot binding");
        var obj = first.getAsJsonArray("objectives").get(0).getAsJsonObject();
        check(obj.getAsJsonArray("scores").get(0).getAsJsonObject().get("value").getAsInt() == 12, "score value");
        check(obj.get("name_component").toString().contains("gold"), "display styling retained");
        check(first.getAsJsonArray("teams").get(0).getAsJsonObject().getAsJsonArray("members").get(0).getAsString().equals("player"), "team members");
        board.getOrCreatePlayerScore(holder, objective).set(3);
        check(LiveData.scoreboard(board, registries).getAsJsonArray("objectives").get(0).getAsJsonObject().getAsJsonArray("scores").get(0).getAsJsonObject().get("value").getAsInt() == 3, "score changes are live");
        board.removeObjective(objective);
        board.removePlayerTeam(team);
        var removed = LiveData.scoreboard(board, registries);
        check(removed.getAsJsonArray("objectives").isEmpty() && removed.getAsJsonArray("teams").isEmpty(), "removed data is not cached");
        check(removed.getAsJsonObject("display_slots").get("sidebar").isJsonNull(), "removed display slot explicit");
    }

    private static void unsupportedMenu() {
        AbstractContainerMenu menu = new AbstractContainerMenu(null, 42) {
            public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) { return ItemStack.EMPTY; }
            public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
        };
        var details = LiveData.menu(menu, null, new ItemView(false));
        check(!details.get("supported").getAsBoolean() && details.get("container_id").getAsInt() == 42, "unsupported menus explicit");
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createWorldLookup();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        PresentationHarness.bindEnchantmentTags(registries);
        items(registries);
        effects();
        scoreboard(registries);
        unsupportedMenu();
        var field = net.minecraft.client.gui.components.BossHealthOverlay.class.getDeclaredField("events");
        check(java.util.Map.class.isAssignableFrom(field.getType()), "bossbar accessor target verified against 26.3");
        System.out.println("PASS " + checks + " live-data checks against Minecraft 26.3");
    }
}
