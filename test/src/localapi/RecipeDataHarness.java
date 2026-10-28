package localapi;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.display.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class RecipeDataHarness {
    static int checks;
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
    static void error(int status,Runnable action){try{action.run();throw new AssertionError("missing error");}catch(HttpError e){check(e.status==status,e.getMessage());}}
    static AbstractContainerMenu menu(int id){return new AbstractContainerMenu(null,id){
        public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player p,int slot){return ItemStack.EMPTY;}
        public boolean stillValid(net.minecraft.world.entity.player.Player p){return true;}
    };}
    @SuppressWarnings("unchecked") static Map<RecipeDisplayId,RecipeDisplayEntry> known(ClientRecipeBook book)throws Exception{
        var f=ClientRecipeBook.class.getDeclaredField("known");f.setAccessible(true);return (Map<RecipeDisplayId,RecipeDisplayEntry>)f.get(book);
    }
    static SlotDisplay STONE;
    static RecipeDisplayEntry shaped(int id,int width,int height){return new RecipeDisplayEntry(new RecipeDisplayId(id),
        new ShapedCraftingRecipeDisplay(width,height,Collections.nCopies(width*height,STONE),new SlotDisplay.ItemSlotDisplay(Items.STONE_BUTTON),new SlotDisplay.ItemSlotDisplay(Items.CRAFTING_TABLE)),
        OptionalInt.empty(),RecipeBookCategories.CRAFTING_MISC,Optional.of(Collections.nCopies(width*height,Ingredient.of(Items.STONE))));}
    static RecipeData.Access access(ScreenWidgetsHarness.TestScreen screen,AbstractContainerMenu menu,ClientRecipeBook book,RecipeData.MenuKind kind,Map<RecipeDisplayId,RecipeDisplayEntry> known,StackedItemContents contents){
        return new RecipeData.Access(screen,menu,book,kind,known,contents,FeatureFlags.DEFAULT_FLAGS);
    }
    static Request read(String... pairs){Map<String,String> query=new HashMap<>();for(int i=0;i<pairs.length;i+=2)query.put(pairs[i],pairs[i+1]);return new Request("GET","/recipes",query,new JsonObject());}
    static Request select(ScreenWidgets screens,RecipeData.Access access,int id){var b=new JsonObject();b.addProperty("revision",screens.revisionFor(access.screen()));b.addProperty("container_id",access.menu().containerId);b.addProperty("id",id);return new Request("POST","/recipes/select",Map.of(),b);}
    public static void main(String[] args)throws Exception{
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();STONE=new SlotDisplay.ItemSlotDisplay(Items.STONE);
        var registries=VanillaRegistries.createWorldLookup();BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        var screens=new ScreenWidgets();var screen=new ScreenWidgetsHarness.TestScreen();var menu=menu(7);var book=new ClientRecipeBook();var known=known(book);var contents=new StackedItemContents();
        var recipe=shaped(3,1,1);book.add(recipe);
        var access=access(screen,menu,book,RecipeData.MenuKind.CRAFTING,known,contents);var current=new AtomicReference<>(access);var sends=new AtomicInteger();
        RecipeData.Placement sender=(a,id,all)->sends.incrementAndGet();
        ContextMap context=ContextMap.EMPTY;
        check(RecipeData.list(read(),access,context,screens).getAsJsonArray("recipes").size()==1,"only actually known entries, without rebuilding cached UI collections");
        var empty=RecipeData.list(read(),access,context,screens).getAsJsonArray("recipes").get(0).getAsJsonObject();
        check(!empty.get("craftable").getAsBoolean() && empty.get("reason").getAsString().equals("missing_ingredients"),"missing ingredients explicit");
        var req=select(screens,access,3);error(409,()->RecipeData.select(req,current::get,screens,sender));check(sends.get()==0,"missing ingredients never sends");
        contents.accountSimpleStack(new ItemStack(Items.STONE,1));
        var live=RecipeData.list(read(),access,context,screens).getAsJsonArray("recipes").get(0).getAsJsonObject();
        check(live.get("craftable").getAsBoolean(),"new inventory reflected without cached craftability");
        check(!live.getAsJsonObject("results").getAsJsonArray("items").get(0).getAsJsonObject().has("details"),"item details absent by default");
        check(RecipeData.list(read("item_details","true"),access,context,screens).getAsJsonArray("recipes").get(0).getAsJsonObject().getAsJsonObject("results").getAsJsonArray("items").get(0).getAsJsonObject().has("details"),"item details opt-in");
        var response=RecipeData.select(req,current::get,screens,sender);
        check(sends.get()==1 && response.get("requested").getAsBoolean() && !response.has("crafted"),"only placement requested, no fabricated crafting result");
        error(409,()->RecipeData.select(req,current::get,screens,sender));check(sends.get()==1,"no duplicate retry");
        var unknown=select(screens,access,99);error(404,()->RecipeData.select(unknown,current::get,screens,sender));
        check(!RecipeData.compatible(shaped(4,3,3),RecipeData.MenuKind.INVENTORY) && RecipeData.compatible(shaped(4,3,3),RecipeData.MenuKind.CRAFTING),"2x2 versus 3x3 fit");
        var shapeless=new RecipeDisplayEntry(new RecipeDisplayId(5),new ShapelessCraftingRecipeDisplay(Collections.nCopies(5,STONE),STONE,STONE),OptionalInt.empty(),RecipeBookCategories.CRAFTING_MISC,Optional.of(List.of()));
        check(!RecipeData.compatible(shapeless,RecipeData.MenuKind.INVENTORY),"shapeless slot count respected");
        var furnace=new RecipeDisplayEntry(new RecipeDisplayId(6),new FurnaceRecipeDisplay(STONE,STONE,STONE,STONE,200,0),OptionalInt.empty(),RecipeBookCategories.SMOKER_FOOD,Optional.of(List.of(Ingredient.of(Items.STONE))));
        check(RecipeData.compatible(furnace,RecipeData.MenuKind.SMOKER) && !RecipeData.compatible(furnace,RecipeData.MenuKind.FURNACE) && !RecipeData.compatible(furnace,RecipeData.MenuKind.BLAST_FURNACE),"furnace categories do not mix");
        var unavailable=new RecipeDisplayEntry(new RecipeDisplayId(8),recipe.display(),OptionalInt.empty(),recipe.category(),Optional.empty());book.add(unavailable);
        var page=RecipeData.list(read("after","3"),access,context,screens).getAsJsonArray("recipes").get(0).getAsJsonObject();
        check(page.get("craftable").isJsonNull() && page.get("requirements").isJsonNull(),"unknown requirements not invented as false or empty");
        error(409,()->RecipeData.select(select(screens,access,8),current::get,screens,sender));
        var closed=access(null,menu,book,null,known,contents);var closedView=RecipeData.list(read(),closed,context,screens);
        check(!closedView.get("supported_menu").getAsBoolean() && closedView.get("revision").isJsonNull(),"known recipes still readable without open supported menu");
        check(closedView.getAsJsonArray("recipes").get(0).getAsJsonObject().get("craftable").isJsonNull(),"no menu means unknown craftability");
        var changedReq=select(screens,access,3);current.set(access(new ScreenWidgetsHarness.TestScreen(),menu,book,RecipeData.MenuKind.CRAFTING,known,contents));
        error(409,()->RecipeData.select(changedReq,current::get,screens,sender));
        current.set(access);var calls=new AtomicInteger();
        error(409,()->RecipeData.select(changedReq,()->calls.getAndIncrement()==0?access:access(screen,menu(7),book,RecipeData.MenuKind.CRAFTING,known,contents),screens,sender));
        check(sends.get()==1,"replacement menu with same numeric ID never sends");
        var mismatch=select(screens,access,3);mismatch.body().addProperty("container_id",8);error(409,()->RecipeData.select(mismatch,current::get,screens,sender));
        var fraction=select(screens,access,3);fraction.body().addProperty("id",3.2);error(400,()->RecipeData.select(fraction,current::get,screens,sender));
        book.remove(recipe.id());error(404,()->RecipeData.select(select(screens,access,3),current::get,screens,sender));
        check(RecipeData.list(read(),access,context,screens).getAsJsonArray("recipes").size()==1,"removed recipe absent immediately");
        book.add(recipe);screens.reset();error(409,()->RecipeData.select(changedReq,current::get,screens,sender));
        var first=RecipeData.list(read("limit","1"),access,context,screens);check(first.get("has_more").getAsBoolean() && first.get("next_after").getAsInt()==3,"paged by actual display ID");
        error(400,()->RecipeData.list(read("limit","201"),access,context,screens));
        // Item availability says nothing about free output space. The server still decides placement.
        for(int i=0;i<36;i++)contents.accountSimpleStack(new ItemStack(Items.DIRT,64));
        var full=RecipeData.select(select(screens,access,3),current::get,screens,sender);
        check(full.has("confirmation") && !full.has("inventory_space") && sends.get()==2,"full contents do not fabricate placement or output capacity");
        var all=select(screens,access,3);all.body().addProperty("all",true);var allSeen=new AtomicBoolean();
        RecipeData.select(all,current::get,screens,(a,id,max)->allSeen.set(max));check(allSeen.get(),"all flag forwarded to normal placement");
        book.clear();check(RecipeData.list(read(),access,context,screens).getAsJsonArray("recipes").isEmpty(),"book reset reads empty live state");
        System.out.println("PASS "+checks+" recipe-book checks");
    }
}
