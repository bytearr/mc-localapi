package localapi;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.*;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.*;
import net.minecraft.client.OptionInstance;
import net.fabricmc.loader.api.*;
import net.fabricmc.loader.api.metadata.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClientMetadataHarness {
    static int checks;
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
    static Request req(String json){return new Request("GET","/mod/icon",Map.of(),JsonParser.parseString(json).getAsJsonObject());}
    static void error(int status,Runnable action){try{action.run();throw new AssertionError("expected error");}catch(HttpError e){check(e.status==status,e.getMessage());}}
    static Holder.Reference<Enchantment> enchant(MappedRegistry<Enchantment> registry,String id,int max,HolderSet<Enchantment> excludes){
        var definition=Enchantment.definition(HolderSet.direct(Items.DIAMOND_HELMET.builtInRegistryHolder()),1,max,Enchantment.constantCost(1),Enchantment.constantCost(10),1,net.minecraft.world.entity.EquipmentSlotGroup.HEAD);
        var value=new Enchantment(Component.translatable("enchantment.minecraft."+id),definition,excludes,DataComponentMap.EMPTY);
        return registry.register(ResourceKey.create(Registries.ENCHANTMENT,Identifier.parse("test:"+id)),value,RegistrationInfo.BUILT_IN);
    }
    static JsonObject view(ItemStack stack,HolderLookup.Provider registry,List<EnchantmentData.Target> targets){var out=new JsonObject();EnchantmentData.add(out,stack,registry,targets);return out;}
    static JsonObject find(JsonArray array,String id){for(var e:array)if(e.getAsJsonObject().get("id").getAsString().equals(id))return e.getAsJsonObject();return null;}
    static void enchantments(){
        var registry=new MappedRegistry<Enchantment>(Registries.ENCHANTMENT,Lifecycle.stable());
        var protection=enchant(registry,"protection",4,HolderSet.direct());
        var blast=enchant(registry,"blast_protection",4,HolderSet.direct(protection));
        var respiration=enchant(registry,"respiration",3,HolderSet.direct());
        enchant(registry,"binding_curse",1,HolderSet.direct());enchant(registry,"mending",1,HolderSet.direct());
        registry.freeze();var lookup=HolderLookup.Provider.create(java.util.stream.Stream.of(registry));
        var helmet=new ItemStack(Items.DIAMOND_HELMET);helmet.enchant(protection,3);
        var out=view(helmet,lookup,List.of());var fits=out.getAsJsonArray("fits");
        check(find(fits,"test:protection").get("level").getAsInt()==3,"existing enchantment can upgrade");
        check(find(fits,"test:protection").get("max_level").getAsInt()==4,"registry max level");
        check(find(fits,"test:blast_protection")==null,"symmetric exclusion rejected");
        check(find(fits,"test:respiration").get("level").getAsInt()==0,"absent compatible enchantment");
        check(find(fits,"test:respiration").get("name").getAsString().equals(respiration.value().description().getString()),"description without level suffix");
        check(find(fits,"test:binding_curse")!=null&&find(fits,"test:mending")!=null,"curse and treasure not filtered");
        helmet.enchant(protection,4);check(find(view(helmet,lookup,List.of()).getAsJsonArray("fits"),"test:protection")==null,"maxed enchantment excluded");
        check(view(new ItemStack(Items.STONE),lookup,List.of()).getAsJsonArray("fits").isEmpty(),"unsupported item excluded");
        var book=new ItemStack(Items.ENCHANTED_BOOK);var stored=new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);stored.set(respiration,2);book.set(DataComponents.STORED_ENCHANTMENTS,stored.toImmutable());
        var targets=List.of(new EnchantmentData.Target(0,helmet),new EnchantmentData.Target(5,helmet),new EnchantmentData.Target(36,new ItemStack(Items.STONE)),new EnchantmentData.Target(45,ItemStack.EMPTY));
        var row=view(book,lookup,targets).getAsJsonArray("book_targets").get(0).getAsJsonObject();
        check(row.get("level").getAsInt()==2&&row.get("max_level").getAsInt()==3,"book level and registry max");
        check(row.getAsJsonArray("slots").toString().equals("[5]"),"only supported nonempty menu slots");
        stored=new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);stored.set(blast,1);book.set(DataComponents.STORED_ENCHANTMENTS,stored.toImmutable());
        check(view(book,lookup,targets).getAsJsonArray("book_targets").get(0).getAsJsonObject().getAsJsonArray("slots").isEmpty(),"book target conflict rejected");
        check(!new ItemView(false).item(helmet).has("fits"),"default items do not grow fits");
        check(!new ItemView(true).item(ItemStack.EMPTY).has("fits"),"empty items do not grow fits");
        var many=new ItemView(true);for(int i=0;i<ItemView.MAX_DETAILS;i++)many.item(new ItemStack(Items.STONE));
        check(!many.item(new ItemStack(Items.STONE)).has("fits"),"truncated items do not grow fits");
    }
    static JsonObject describe(OptionInstance<?> option){return OptionsData.describe(new OptionsData.Entry("video",option),Component.translatable("test.option"),v->Component.literal("vanilla="+v));}
    static void options(){
        var integer=new OptionInstance<Integer>("test.option",OptionInstance.noTooltip(),(c,v)->Component.literal("native="+v),new OptionInstance.IntRange(0,10),0,v->{});
        var row=describe(integer);check(row.get("value").getAsInt()==0&&row.get("min").getAsInt()==0&&row.get("max").getAsInt()==10,"stored integer and range unchanged");
        check(row.get("slider").getAsDouble()==0&&row.get("label").getAsString().equals("vanilla=0"),"slider and supplied vanilla formatter");
        error(400,()->OptionsData.apply(integer,new JsonPrimitive(11)));check(integer.get()==0,"rejected value keeps previous integer");
        error(400,()->OptionsData.apply(integer,new JsonPrimitive(1.5)));error(400,()->OptionsData.apply(integer,new JsonPrimitive("2")));
        var max=new AtomicInteger(3);var dynamic=new OptionInstance<Integer>("test.dynamic",OptionInstance.noTooltip(),(c,v)->c,new OptionInstance.ClampingLazyMaxIntRange(0,max::get,20),0,v->{});
        check(describe(dynamic).get("max").getAsInt()==3,"dynamic max initial");max.set(8);check(describe(dynamic).get("max").getAsInt()==8,"dynamic max read live");
        var bool=OptionInstance.createBoolean("test.bool",true);row=describe(bool);check(row.get("kind").getAsString().equals("boolean")&&!row.has("min")&&!row.has("max")&&!row.has("slider"),"boolean has no numeric range/slider");
        error(400,()->OptionsData.apply(bool,new JsonPrimitive(1)));check(bool.get(),"invalid boolean unchanged");
        var number=new OptionInstance<Double>("test.double",OptionInstance.noTooltip(),(c,v)->c,OptionInstance.UnitDouble.INSTANCE,0.25,v->{});row=describe(number);
        check(row.get("value").getAsDouble()==0.25&&row.get("slider").getAsDouble()==0.25,"double remains stored units");
        error(400,()->OptionsData.apply(number,new JsonPrimitive(2.0)));error(400,()->OptionsData.apply(number,new JsonPrimitive(Double.NaN)));
        var choice=new OptionInstance<String>("test.enum",OptionInstance.noTooltip(),(c,v)->c,new OptionInstance.Enum<>(List.of("second","first"),Codec.STRING),"second",v->{});row=describe(choice);
        check(row.getAsJsonArray("values").get(0).getAsJsonObject().get("id").getAsString().equals("second"),"enum preserves screen order");
        check(row.getAsJsonArray("values").get(1).getAsJsonObject().get("name").getAsString().equals("vanilla=first"),"enum uses own captions");
        error(400,()->OptionsData.apply(choice,new JsonPrimitive("other")));check(choice.get().equals("second"),"invalid enum unchanged");
    }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);}
    static ModContainer mod(String id,Path root,String icon)throws Exception{
        var person=proxy(Person.class,(o,m,a)->{if(m.getName().equals("getName"))return "Author";throw new AssertionError("private person metadata accessed");});
        var metadata=proxy(ModMetadata.class,(o,m,a)->switch(m.getName()){
            case "getId"->id;case "getName"->"Name "+id;case "getVersion"->Version.parse("1.2.3");case "getDescription"->"Description";case "getAuthors"->List.of(person);case "getEnvironment"->ModEnvironment.UNIVERSAL;case "getIconPath"->Optional.ofNullable(icon);default->throw new AssertionError("private mod metadata accessed: "+m.getName());
        });
        return proxy(ModContainer.class,(o,m,a)->switch(m.getName()){
            case "getMetadata"->metadata;case "getRootPaths"->List.of(root);case "findPath"->Optional.of(root.resolve((String)a[0]));default->throw new AssertionError("unexpected mod container access");
        });
    }
    static void mods()throws Exception{
        Path root=Files.createTempDirectory("localapi-mod-test");
        try{
            byte[] png=ItemIcons.encode(java.nio.ByteBuffer.wrap(new byte[]{1,2,3,(byte)255}),1,1);Files.write(root.resolve("icon.png"),png);
            var a=mod("a",root,"icon.png");var z=mod("z",root,null);
            var rows=ModData.list(List.of(z,a)).getAsJsonArray("mods");
            check(rows.size()==2&&rows.get(0).getAsJsonObject().get("id").getAsString().equals("a"),"mod list sorted, all entries included");
            check(rows.get(0).getAsJsonObject().size()==7,"only seven public metadata fields");
            check(rows.get(0).getAsJsonObject().getAsJsonArray("authors").toString().equals("[\"Author\"]"),"author names only");
            check(!rows.get(1).getAsJsonObject().get("has_icon").getAsBoolean(),"missing icon metadata explicit");
            check(Arrays.equals(ModData.icon(req("{\"id\":\"a\"}"),List.of(a)).bytes(),png),"raw metadata-selected PNG");
            error(404,()->ModData.icon(req("{\"id\":\"missing\"}"),List.of(a)));error(404,()->ModData.icon(req("{\"id\":\"z\"}"),List.of(z)));
            Files.writeString(root.resolve("icon.png"),"not png");error(404,()->ModData.icon(req("{\"id\":\"a\"}"),List.of(a)));
            byte[] large=Arrays.copyOf(png,ModData.MAX_ICON_BYTES+1);Files.write(root.resolve("icon.png"),large);error(404,()->ModData.icon(req("{\"id\":\"a\"}"),List.of(a)));
            Files.write(root.resolve("icon.png"),Arrays.copyOf(png,ModData.MAX_ICON_BYTES));check(ModData.icon(req("{\"id\":\"a\"}"),List.of(a)).bytes().length==ModData.MAX_ICON_BYTES,"exact byte limit accepted");
            Files.delete(root.resolve("icon.png"));error(404,()->ModData.icon(req("{\"id\":\"a\"}"),List.of(a)));
            var traversal=mod("bad",root,"../outside.png");error(404,()->ModData.icon(req("{\"id\":\"bad\"}"),List.of(traversal)));
            check(!ModData.validPng(new byte[]{(byte)0x89,0x50,0x4e,0x47}),"truncated signature rejected");
        }finally{try(var files=Files.walk(root)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    static void routes()throws Exception{
        check(Router.docs().stream().filter(r->r.path().equals("/options")).map(Router.RouteDoc::method).collect(java.util.stream.Collectors.toSet()).equals(Set.of("GET","POST")),"both options methods documented");
        var list=(JsonArray)Router.dispatch(new Request("GET","/routes",Map.of(),new JsonObject()));check(list.contains(new JsonPrimitive("GET  /options"))&&list.contains(new JsonPrimitive("POST /options")),"both methods registered");
        var batch=(JsonArray)BatchRequests.dispatch(new Request("POST","/batch",Map.of(),JsonParser.parseString("{\"requests\":[{\"path\":\"/mod/icon/\"}]}").getAsJsonObject()),r->{throw new AssertionError("icon executed in batch");});check(!batch.get(0).getAsJsonObject().get("ok").getAsBoolean(),"mod icon excluded from batch");
        var field=Router.class.getDeclaredField("ROUTES");field.setAccessible(true);
        var overridesField=Router.class.getDeclaredField("POST_OVERRIDES");overridesField.setAccessible(true);
        @SuppressWarnings("unchecked") var routes=(Map<String,Object>)field.get(null);
        @SuppressWarnings("unchecked") var overrides=(Map<String,Object>)overridesField.get(null);
        Object read=routes.get("/options"),write=overrides.get("/options");
        var ctor=read.getClass().getDeclaredConstructor(boolean.class,boolean.class,Router.Handler.class);ctor.setAccessible(true);
        try {
            routes.put("/options",ctor.newInstance(false,false,(Router.Handler)r->"read"));
            overrides.put("/options",ctor.newInstance(true,false,(Router.Handler)r->"write"));
            check(Router.dispatch(new Request("GET","/options/",Map.of(),new JsonObject())).equals("read"),"GET selects options reader");
            check(Router.dispatch(new Request("POST","/options/",Map.of(),new JsonObject())).equals("write"),"POST selects options writer");
            var invalidates=write.getClass().getDeclaredMethod("invalidatesGameState");invalidates.setAccessible(true);
            check(!(boolean)invalidates.invoke(write),"options writes do not invalidate unrelated game state");
            check(!(boolean)invalidates.invoke(routes.get("/stats/sync")),"stats sync does not invalidate unrelated game state");
        } finally {routes.put("/options",read);overrides.put("/options",write);}
        error(409,()->StatsSync.request(null));check(!StatsSync.received(null),"no connection has not received stats");
    }
    public static void main(String[] args)throws Exception{
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();var r=net.minecraft.data.registries.VanillaRegistries.createWorldLookup();BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(r).forEach(DataComponentInitializers.PendingComponents::apply);
        enchantments();options();mods();routes();System.out.println("PASS "+checks+" client metadata checks");
    }
}
