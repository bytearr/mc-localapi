package localapi;

import com.google.gson.*;
import localapi.mixin.ClientRecipeBookAccessor;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.display.*;

import java.util.*;
import java.util.function.Supplier;

/** Client-known recipe displays and ordinary placement requests; never a crafting-result cache. */
final class RecipeData {
    enum MenuKind { INVENTORY, CRAFTING, FURNACE, BLAST_FURNACE, SMOKER }
    record Access(Screen screen, AbstractContainerMenu menu, ClientRecipeBook book, MenuKind kind,
                  Map<RecipeDisplayId, RecipeDisplayEntry> known, StackedItemContents contents, FeatureFlagSet flags) {}
    @FunctionalInterface interface Placement { void send(Access access, RecipeDisplayId id, boolean all); }

    static Access live(Minecraft mc) {
        if (mc.player == null || mc.level == null) throw new HttpError(409, "not in a world");
        var menu = mc.player.containerMenu;
        Screen screen = mc.gui.overlay() == null ? mc.gui.screen() : null;
        MenuKind kind = null;
        if (screen instanceof AbstractRecipeBookScreen<?> recipeScreen && recipeScreen.getMenu() == menu) {
            if (menu.getClass() == InventoryMenu.class) kind = MenuKind.INVENTORY;
            else if (menu.getClass() == CraftingMenu.class) kind = MenuKind.CRAFTING;
            else if (menu.getClass() == FurnaceMenu.class) kind = MenuKind.FURNACE;
            else if (menu.getClass() == BlastFurnaceMenu.class) kind = MenuKind.BLAST_FURNACE;
            else if (menu.getClass() == SmokerMenu.class) kind = MenuKind.SMOKER;
        }
        var contents = new StackedItemContents();
        if (kind != null) {
            mc.player.getInventory().fillStackedContents(contents);
            ((RecipeBookMenu) menu).fillCraftSlotsStackedContents(contents);
        }
        var book = mc.player.getRecipeBook();
        return new Access(screen, menu, book, kind, ((ClientRecipeBookAccessor) book).localapi$known(), contents, mc.level.enabledFeatures());
    }

    static JsonObject select(Request request, Minecraft mc) {
        if (mc.gameMode == null) throw new HttpError(409, "no active game mode");
        return select(request, () -> live(mc), ScreenWidgets.INSTANCE, (access, id, all) -> {
            var recipes = new net.minecraft.client.gui.screens.recipebook.RecipeCollection(List.of(access.known.get(id)));
            recipes.selectRecipes(access.contents, display -> true);
            var book = ((localapi.mixin.RecipeBookScreenAccessor) access.screen).localapi$recipeBook();
            if (!((localapi.mixin.RecipeBookPlacementInvoker) book).localapi$place(recipes, id, all))
                throw new HttpError(409, "recipe book declined placement");
        });
    }

    static JsonObject list(Request request, Access access, ContextMap context, ScreenWidgets screens) {
        int after = integer(request, "after", -1), limit = integer(request, "limit", 100);
        if (after < -1 || limit < 1 || limit > 200) throw new HttpError(400, "after >= -1; limit must be 1..200");
        JsonObject out = new JsonObject();
        out.addProperty("supported_menu", access.kind != null);
        out.addProperty("menu_kind", access.kind == null ? null : access.kind.name().toLowerCase(Locale.ROOT));
        out.addProperty("container_id", access.menu.containerId);
        out.addProperty("revision", access.kind == null ? null : screens.revisionFor(access.screen));
        if (access.kind == null) out.addProperty("reason", "no_supported_recipe_screen");
        var selected = access.known.values().stream().filter(e -> e.id().index() > after)
                .sorted(Comparator.comparingInt(e -> e.id().index())).limit(limit + 1L).toList();
        ItemView items = new ItemView(request.bool("item_details", false));
        JsonArray recipes = new JsonArray();
        int next = after;
        for (var entry : selected.subList(0, Math.min(limit, selected.size()))) {
            JsonObject value = new JsonObject();
            next = entry.id().index();
            value.addProperty("id", next);
            value.addProperty("display_type", Objects.toString(BuiltInRegistries.RECIPE_DISPLAY.getKey(entry.display().type()), null));
            value.addProperty("category", Objects.toString(BuiltInRegistries.RECIPE_BOOK_CATEGORY.getKey(entry.category()), null));
            value.addProperty("highlighted", access.book.willHighlight(entry.id()));
            String reason = reason(entry, access.kind, access.contents, access.flags);
            value.addProperty("compatible", access.kind == null ? null : compatible(entry, access.kind));
            Boolean craftable = access.kind == null || entry.craftingRequirements().isEmpty() ? null : reason == null;
            value.addProperty("craftable", craftable);
            value.addProperty("reason", reason);
            value.add("results", results(entry.display().result(), context, items));
            if (entry.display() instanceof ShapedCraftingRecipeDisplay shaped) {
                value.addProperty("width", shaped.width()); value.addProperty("height", shaped.height());
            }
            value.add("requirements", requirements(entry));
            recipes.add(value);
        }
        out.add("recipes", recipes);
        out.addProperty("next_after", next);
        out.addProperty("has_more", selected.size() > limit);
        return out;
    }

    private static JsonObject results(SlotDisplay display, ContextMap context, ItemView items) {
        JsonObject out = new JsonObject();
        JsonArray stacks = new JsonArray();
        try (var resolved = display.resolve(context, SlotDisplay.ItemStackContentsFactory.INSTANCE)) {
            var bounded = resolved.limit(33).toList();
            bounded.stream().limit(32).forEach(stack -> stacks.add(items.item(stack)));
            out.addProperty("truncated", bounded.size() > 32);
        }
        out.add("items", stacks);
        return out;
    }

    private static JsonElement requirements(RecipeDisplayEntry entry) {
        if (entry.craftingRequirements().isEmpty()) return JsonNull.INSTANCE;
        JsonObject out = new JsonObject(); JsonArray slots = new JsonArray();
        var requirements = entry.craftingRequirements().get();
        for (var ingredient : requirements.stream().limit(64).toList()) {
            JsonObject slot = new JsonObject(); JsonArray alternatives = new JsonArray();
            var values = ingredient.items().limit(129).toList();
            values.stream().limit(128).forEach(item -> alternatives.add(ItemView.holderId(item)));
            slot.add("alternatives", alternatives); slot.addProperty("truncated", values.size() > 128);
            slots.add(slot);
        }
        out.add("ingredients", slots); out.addProperty("truncated", requirements.size() > 64);
        return out;
    }

    static boolean compatible(RecipeDisplayEntry entry, MenuKind kind) {
        if (kind == null) return false;
        var display = entry.display(); var category = entry.category();
        if (kind == MenuKind.CRAFTING || kind == MenuKind.INVENTORY) {
            if (!Set.of(RecipeBookCategories.CRAFTING_BUILDING_BLOCKS, RecipeBookCategories.CRAFTING_EQUIPMENT,
                    RecipeBookCategories.CRAFTING_MISC, RecipeBookCategories.CRAFTING_REDSTONE).contains(category)) return false;
            int size = kind == MenuKind.INVENTORY ? 2 : 3;
            if (display instanceof ShapedCraftingRecipeDisplay shaped) return shaped.width() <= size && shaped.height() <= size;
            if (display instanceof ShapelessCraftingRecipeDisplay shapeless) return shapeless.ingredients().size() <= size * size;
            return false;
        }
        if (!(display instanceof FurnaceRecipeDisplay)) return false;
        return switch (kind) {
            case FURNACE -> Set.of(RecipeBookCategories.FURNACE_FOOD, RecipeBookCategories.FURNACE_BLOCKS, RecipeBookCategories.FURNACE_MISC).contains(category);
            case BLAST_FURNACE -> category == RecipeBookCategories.BLAST_FURNACE_BLOCKS || category == RecipeBookCategories.BLAST_FURNACE_MISC;
            case SMOKER -> category == RecipeBookCategories.SMOKER_FOOD;
            default -> false;
        };
    }

    static String reason(RecipeDisplayEntry entry, MenuKind kind, StackedItemContents contents, FeatureFlagSet flags) {
        if (kind == null) return "no_supported_recipe_screen";
        if (!compatible(entry, kind)) return "incompatible_menu";
        if (!entry.display().isEnabled(flags)) return "disabled_features";
        if (entry.craftingRequirements().isEmpty()) return "requirements_unavailable";
        return entry.canCraft(contents) ? null : "missing_ingredients";
    }

    static JsonObject select(Request request, Supplier<Access> live, ScreenWidgets screens, Placement placement) {
        String revision = request.reqStr("revision");
        int containerId = integer(request, "container_id", null), id = integer(request, "id", null);
        if (id < 0 || containerId < 0) throw new HttpError(400, "id and container_id must be nonnegative");
        boolean all = request.bool("all", false);
        Access access = live.get();
        validate(access, revision, containerId, id, screens);
        // Re-read after validation, and compare object identity rather than trusting reused numeric IDs.
        Access fresh = live.get();
        if (fresh.screen != access.screen || fresh.menu != access.menu || fresh.book != access.book)
            throw new HttpError(409, "screen/container/connection changed");
        validate(fresh, revision, containerId, id, screens);
        screens.consumeRevision(fresh.screen, revision);
        placement.send(fresh, new RecipeDisplayId(id), all);
        ClientEvents.invalidate("/recipes", "/container", "/inventory", "/screen");
        JsonObject out = new JsonObject();
        out.addProperty("requested", true); out.addProperty("id", id); out.addProperty("container_id", containerId);
        out.addProperty("all", all); out.addProperty("confirmation", "read_live_container_after_server_update");
        return out;
    }

    private static void validate(Access access, String revision, int containerId, int id, ScreenWidgets screens) {
        screens.requireRevision(access.screen, revision);
        if (access.menu.containerId != containerId) throw new HttpError(409, "container_id changed");
        var entry = access.known.get(new RecipeDisplayId(id));
        if (entry == null) throw new HttpError(404, "recipe display is not known to this client");
        String reason = reason(entry, access.kind, access.contents, access.flags);
        if (reason != null) {
            JsonObject extra = new JsonObject(); extra.addProperty("reason", reason);
            throw new HttpError(409, "cannot select recipe: " + reason, extra);
        }
    }

    private static int integer(Request request, String name, Integer fallback) {
        if (!request.has(name)) {
            if (fallback == null) throw new HttpError(400, "missing parameter: " + name);
            return fallback;
        }
        double value = request.dbl(name, 0);
        if (value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new HttpError(400, name + " must be an integer");
        return (int) value;
    }
}
