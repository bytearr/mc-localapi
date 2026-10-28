package localapi;

import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.registries.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.*;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.effect.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.advancements.*;
import net.minecraft.client.multiplayer.ClientAdvancements;
import net.minecraft.stats.*;
import java.util.*;

public final class PresentationHarness {
    static int checks;
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
    static Request req(String json){return new Request("GET","/item/icon",Map.of(),JsonParser.parseString(json).getAsJsonObject());}
    static void error(int status,Runnable r){try{r.run();throw new AssertionError("expected error");}catch(HttpError e){check(e.status==status,e.getMessage());}}
    // VanillaRegistries has unbound data-pack tags in a headless bootstrap; live clients receive them from the server.
    static void bindEnchantmentTags(net.minecraft.core.HolderLookup.Provider registries) throws Exception {
        var bind=net.minecraft.core.Holder.Reference.class.getDeclaredMethod("bindTags",java.util.Collection.class);bind.setAccessible(true);
        for(var ref:registries.lookupOrThrow(Registries.ENCHANTMENT).listElements().toList())bind.invoke(ref,Set.of());
    }
    static final class Counter extends StatsCounter implements localapi.mixin.StatsCounterAccessor {
        public it.unimi.dsi.fastutil.objects.Object2IntMap<Stat<?>> localapi$stats(){return stats;}
    }
    static void tints() {
        var colors=net.minecraft.client.color.block.BlockColors.createDefault();
        var world=(net.minecraft.client.renderer.block.BlockAndTintGetter)java.lang.reflect.Proxy.newProxyInstance(
                PresentationHarness.class.getClassLoader(),new Class<?>[]{net.minecraft.client.renderer.block.BlockAndTintGetter.class},(proxy,method,args)->{
                    if(method.getName().equals("getBlockTint"))return ((BlockPos)args[0]).getX()==0?0x123456:0xabcdef;
                    if(method.getName().equals("getBlockState"))return Blocks.AIR.defaultBlockState();
                    throw new AssertionError("unexpected lookup: "+method.getName());
                });
        for(var block:List.of(Blocks.GRASS_BLOCK,Blocks.OAK_LEAVES,Blocks.WATER)) {
            var palette=new BlockPalette(colors);var state=block.defaultBlockState();
            int first=palette.add(state,world,BlockPos.ZERO),second=palette.add(state,world,new BlockPos(1,0,0));
            check(first!=second,"same block different world tints split palette");
            var out=new JsonObject();palette.write(out);var entries=out.getAsJsonArray("palette_entries");
            check(entries.get(first).getAsJsonObject().get("map_color").getAsInt()==0x123456,"first world tint exact");
            check(entries.get(second).getAsJsonObject().get("map_color").getAsInt()==0xabcdef,"second world tint exact");
        }
        var palette=new BlockPalette(colors);palette.add(Blocks.STONE.defaultBlockState(),world,BlockPos.ZERO);
        var out=new JsonObject();palette.write(out);
        check(out.getAsJsonArray("palette_entries").get(0).getAsJsonObject().get("map_color").getAsInt()==Blocks.STONE.defaultBlockState().getMapColor(world,BlockPos.ZERO).col,"untinted block retains material color");
        colors.register(List.of(net.minecraft.client.color.block.BlockTintSources.constant(-1),net.minecraft.client.color.block.BlockTintSources.constant(0xff987654)),Blocks.STONE);
        palette=new BlockPalette(colors);palette.add(Blocks.STONE.defaultBlockState(),world,BlockPos.ZERO);out=new JsonObject();palette.write(out);
        check(out.getAsJsonArray("palette_entries").get(0).getAsJsonObject().get("map_color").getAsInt()==0x987654,"registered tint skips blank layers and strips alpha");
    }

    @SuppressWarnings("unchecked")
    static void http(byte[] png) throws Exception {
        var f=Router.class.getDeclaredField("ROUTES");f.setAccessible(true);
        var routes=(Map<String,Object>)f.get(null);Object original=routes.get("/item/icon");
        var ctor=original.getClass().getDeclaredConstructor(boolean.class,boolean.class,Router.Handler.class);ctor.setAccessible(true);
        Router.Handler handler=r->{if(r.has("missing"))throw new HttpError(404,"unknown item id");return java.util.concurrent.CompletableFuture.completedFuture(new ItemIcons.Png(png));};
        routes.put("/item/icon",ctor.newInstance(false,false,handler));
        Config cfg=new Config();cfg.port=0;var server=new ApiServer(cfg);server.start();
        var port=ApiServer.class.getDeclaredField("boundPort");port.setAccessible(true);
        String url="http://127.0.0.1:"+port.get(server)+"/item/icon";
        try(var client=java.net.http.HttpClient.newHttpClient()){
            var unauthorized=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            check(unauthorized.statusCode()==401,"PNG endpoint retains authentication");
            for(boolean missing:List.of(false,true)){
                var call=client.sendAsync(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url+(missing?"?missing=1":""))).header("X-Password",cfg.password).build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                long deadline=System.nanoTime()+5_000_000_000L;
                while(!call.isDone()&&System.nanoTime()<deadline){GameQueue.drain();Thread.sleep(1);}
                var response=call.get(1,java.util.concurrent.TimeUnit.SECONDS);
                check(response.statusCode()==(missing?404:200),"binary route status");
                check(response.headers().firstValue("Cache-Control").orElse("").equals("no-store"),"PNG/error not cached");
                if(!missing){check(response.headers().firstValue("Content-Type").orElse("").equals("image/png"),"actual PNG media type");check(Arrays.equals(response.body(),png),"raw PNG without JSON or base64 envelope");}
                else check(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),"missing icon retains JSON error");
            }
        }finally{server.stop();routes.put("/item/icon",original);}
    }

    public static void main(String[] args)throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var registries=VanillaRegistries.createWorldLookup();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        bindEnchantmentTags(registries);
        var sword=new ItemStack(Items.DIAMOND_SWORD);sword.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS),3);
        check(!new ItemView(false).item(sword).has("tooltip"),"compact items unchanged");
        check(!new ItemView(true).item(ItemStack.EMPTY).has("tooltip"),"empty items unchanged");
        var detailed=new ItemView(true).item(sword);
        var vanilla=sword.getTooltipLines(Item.TooltipContext.EMPTY,null,TooltipFlag.NORMAL);
        var tooltip=detailed.getAsJsonArray("tooltip");check(tooltip.size()==vanilla.size(),"all vanilla tooltip lines");
        for(int i=0;i<vanilla.size();i++)check(tooltip.get(i).getAsJsonObject().get("text").getAsString().equals(vanilla.get(i).getString()),"tooltip order/text");
        check(tooltip.toString().contains("Sharpness"),"enchantment tooltip included");
        check(tooltip.toString().contains("Attack Damage"),"attribute tooltip included");
        var mixed=Component.literal("red").withColor(0xff0000).append(Component.literal("blue").withColor(0x0000ff));
        var line=ItemView.tooltipLines(List.of(mixed)).get(0).getAsJsonObject();
        check(line.get("color").getAsInt()==0xff0000,"line root color");
        check(line.getAsJsonArray("runs").get(1).getAsJsonObject().get("color").getAsInt()==0xff,"mixed style color retained");
        var saved=Language.getInstance();
        try {
            Language.inject(new Language(){
                final Map<String,String> translations=Map.of("item.minecraft.diamond_sword","Diamantschwert","effect.minecraft.speed","Schnelligkeit","enchantment.minecraft.sharpness","Schärfe","biome.minecraft.plains","Ebene","stat.minecraft.play_time","Spielzeit");
                public String getOrDefault(String k,String fallback){return translations.getOrDefault(k,saved.getOrDefault(k,fallback));}
                public boolean has(String k){return translations.containsKey(k)||saved.has(k);}
                public boolean isDefaultRightToLeft(){return false;}
                public net.minecraft.util.FormattedCharSequence getVisualOrder(net.minecraft.network.chat.FormattedText t){return saved.getVisualOrder(t);}
            });
            var german=new ItemView(true).item(sword);
            check(german.getAsJsonArray("tooltip").toString().contains("Diamantschwert"),"current language tooltip");
            check(german.getAsJsonObject("details").getAsJsonArray("enchantments").get(0).getAsJsonObject().get("name").getAsString().contains("Schärfe"),"current language enchantment");
            check(ItemView.effect(new MobEffectInstance(MobEffects.SPEED,20)).get("name").getAsString().equals("Schnelligkeit"),"current language effect");
            check(ClientNames.biome("minecraft:plains").equals("Ebene"),"current language biome");
            check(ClientNames.criterion("minecraft:diamond_sword").equals("Diamantschwert"),"known registry criterion");
            check(ClientNames.stat(Stats.CUSTOM.get(Stats.PLAY_TIME)).equals("Spielzeit"),"current language statistic");
        } finally {Language.inject(saved);}
        check(new ItemView(true).item(sword).getAsJsonArray("tooltip").toString().contains("Diamond Sword"),"language changes are not cached");
        check(ClientNames.criterion("some_trigger")==null && ClientNames.criterion("missing:unknown")==null,"unknown criteria never labeled");
        check(ClientNames.criterion("minecraft:zombie")!=null,"entity criterion");
        var c=new Counter();var play=Stats.CUSTOM.get(Stats.PLAY_TIME);c.setValue(null,play,72000);
        var stat=ProgressData.statistics(c).getAsJsonArray("stats").get(0).getAsJsonObject();
        check(stat.get("value").getAsInt()==72000,"raw ticks preserved");check(stat.get("formatted_value").getAsString().equals(play.format(72000)),"vanilla statistic formatter");
        var display=new DisplayInfo(new ItemStackTemplate(Items.DIAMOND),Component.literal("Title"),Component.literal("Description"),Optional.empty(),AdvancementType.TASK,false,false,false);
        var client=new ClientAdvancements(null,null);var requirements=new AdvancementRequirements(List.of(List.of("minecraft:diamond_sword"),List.of("custom_trigger")));
        var shown=new AdvancementHolder(Identifier.parse("test:shown"),new Advancement(Optional.empty(),Optional.of(display),AdvancementRewards.EMPTY,Map.of(),requirements,false));
        var hidden=new AdvancementHolder(Identifier.parse("test:hidden"),new Advancement(Optional.empty(),Optional.empty(),AdvancementRewards.EMPTY,Map.of(),requirements,false));
        client.tree().addAll(List.of(shown,hidden));var progress=new AdvancementProgress();progress.update(requirements);client.progress().put(shown,progress);
        for(var value:ProgressData.advancements(client).getAsJsonArray("advancements")){
            var entry=value.getAsJsonObject();if(entry.get("id").getAsString().equals("test:shown")){
                check(entry.get("icon").getAsString().equals("minecraft:diamond"),"advancement icon from display");
                check(entry.getAsJsonObject("criterion_names").size()==1,"only known criterion name added");
                check(entry.getAsJsonObject("criteria").get("custom_trigger").getAsBoolean()==false,"old criteria booleans preserved");
            }else check(!entry.has("icon"),"no display no icon");
        }
        var pal=new BlockPalette(net.minecraft.client.color.block.BlockColors.createDefault());int stone=pal.add(Blocks.STONE.defaultBlockState(),EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
        check(stone==pal.add(Blocks.STONE.defaultBlockState(),EmptyBlockGetter.INSTANCE,BlockPos.ZERO),"palette deduplicates");
        pal.add(Blocks.DIRT.defaultBlockState(),EmptyBlockGetter.INSTANCE,BlockPos.ZERO);var out=new JsonObject();pal.write(out);
        check(out.getAsJsonArray("palette").get(0).getAsString().equals("minecraft:stone"),"legacy palette strings preserved");
        check(out.getAsJsonArray("palette_entries").get(0).getAsJsonObject().get("map_color").getAsInt()==Blocks.STONE.defaultBlockState().getMapColor(EmptyBlockGetter.INSTANCE,BlockPos.ZERO).col,"client map color");
        check(SurfaceData.columns(-1,0,-1,0)==1024,"negative chunk rectangle");check(SurfaceData.columns(15,0,15,0)==65536,"reversed max rectangle");
        error(400,()->SurfaceData.columns(0,16,0,15));error(400,()->SurfaceData.columns(Integer.MIN_VALUE,Integer.MAX_VALUE,0,0));
        check(ItemIcons.stack(req("{\"id\":\"minecraft:diamond\"}"),null).is(Items.DIAMOND),"registry icon stack");
        error(404,()->ItemIcons.stack(req("{\"id\":\"missing:unknown\"}"),null));error(404,()->ItemIcons.stack(req("{\"id\":\"minecraft:air\"}"),null));
        error(400,()->ItemIcons.stack(req("{}"),null));error(400,()->ItemIcons.stack(req("{\"id\":\"minecraft:stone\",\"slot\":0}"),null));
        var rgba=java.nio.ByteBuffer.wrap(new byte[]{(byte)255,0,0,(byte)255,0,(byte)255,0,0,0,0,(byte)128,(byte)128,(byte)255,(byte)255,(byte)255,(byte)255});
        byte[] png=ItemIcons.encode(rgba,2,2);var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
        check(image.getRGB(0,1)==0xffff0000,"PNG red channel and vertical orientation");
        check(image.getRGB(0,0)==0x800000ff,"PNG premultiplied tint converted to straight alpha");check(image.getRGB(1,1)==0x00000000,"PNG transparent pixel preserved");
        var batch=(JsonArray)BatchRequests.dispatch(new Request("POST","/batch",Map.of(),JsonParser.parseString("{\"requests\":[{\"path\":\"/item/icon/\"}]}").getAsJsonObject()),r->{throw new AssertionError("icon dispatched inside batch");});
        check(!batch.get(0).getAsJsonObject().get("ok").getAsBoolean(),"binary route blocked in batch");
        tints();
        http(png);
        System.out.println("PASS "+checks+" presentation checks");
    }
}
