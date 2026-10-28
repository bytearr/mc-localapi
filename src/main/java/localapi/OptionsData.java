package localapi;

import com.google.gson.*;
import localapi.mixin.*;
import net.minecraft.client.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.sounds.SoundSource;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import java.util.function.Function;

/** Live vanilla option instances and their own formatters/validators, never an options.txt editor. */
final class OptionsData {
    record Entry(String group, OptionInstance<?> option) {}
    private static void add(List<Entry> entries, String group, OptionInstance<?>... options) {
        for (var option : options) entries.add(new Entry(group, option));
    }
    static List<Entry> catalog(Minecraft mc) {
        var o=mc.options; List<Entry> entries=new ArrayList<>();
        add(entries,"video",fullscreenMode(mc));
        add(entries,"video",VideoOptionsInvoker.localapi$display(o));
        add(entries,"video",o.graphicsPreset(),o.fov());
        add(entries,"video",VideoOptionsInvoker.localapi$quality(o));
        add(entries,"video",VideoOptionsInvoker.localapi$preferences(o));
        for(SoundSource source:SoundSource.values())add(entries,"sound",o.getSoundSourceOptionInstance(source));
        add(entries,"sound",o.soundDevice(),o.showSubtitles(),o.directionalAudio(),o.musicFrequency(),o.musicToast());
        add(entries,"mouse",MouseSettingsScreenInvoker.localapi$options(o));
        add(entries,"chat",ChatOptionsScreenInvoker.localapi$options(o));
        add(entries,"skin",o.mainHand());
        add(entries,"accessibility",AccessibilityOptionsScreenInvoker.localapi$options(o));
        add(entries,"online",o.inGameNotification(),o.sharePresence(),o.allowServerListing(),o.realmsNotifications());
        add(entries,"font",FontOptionsScreenInvoker.localapi$options(o));
        return entries;
    }
    // This screen-local OptionInstance is constructed just as VideoSettingsScreen does, from the current monitor.
    private static OptionInstance<Integer> fullscreenMode(Minecraft mc) {
        var window=mc.getWindow(); var monitor=window.findBestMonitor();
        int current=monitor==null?-1:window.getPreferredFullscreenVideoMode().map(monitor::indexOfMode).orElse(-1);
        return new OptionInstance<>("options.fullscreen.exclusive.mode",OptionInstance.noTooltip(),(caption,value)->{
            if(monitor==null)return Component.translatable("options.fullscreen.unavailable");
            if(value==-1)return Options.genericValueLabel(caption,Component.translatable("options.fullscreen.current"));
            var mode=monitor.mode(value);
            return Options.genericValueLabel(caption,Component.translatable("options.fullscreen.entry",mode.getWidth(),mode.getHeight(),mode.refreshRateLabel(),mode.getRedBits()+mode.getGreenBits()+mode.getBlueBits()));
        },new OptionInstance.IntRange(-1,monitor==null?-1:monitor.modeCount()-1),current,value->{
            if(monitor!=null)window.setPreferredFullscreenVideoMode(value==-1?Optional.empty():Optional.of(monitor.mode(value)));
        });
    }
    static JsonObject read(Minecraft mc) {
        JsonArray options=new JsonArray();Set<String> seen=new HashSet<>();
        for(Entry entry:catalog(mc)) {
            var row=describe(entry,((OptionInstanceAccessor)(Object)entry.option).localapi$caption(),((OptionInstanceAccessor)(Object)entry.option).localapi$formatter());
            if(seen.add(row.get("id").getAsString()))options.add(row);
        }
        // Skin part toggles are CycleButtons rather than OptionInstances; use the same values and captions.
        for(var part:net.minecraft.world.entity.player.PlayerModelPart.values()) {
            var caption=part.getName();String id=((TranslatableContents)caption.getContents()).getKey();boolean value=mc.options.isModelPartEnabled(part);
            JsonObject row=base(id,caption.getString(),"skin","boolean",new JsonPrimitive(value),net.minecraft.network.chat.CommonComponents.optionStatus(caption,value).getString());options.add(row);
        }
        var manager=mc.getLanguageManager();var selected=manager.getLanguage(mc.options.languageCode);
        JsonObject language=base("language",Component.translatable("options.language").getString(),"language","language",new JsonPrimitive(mc.options.languageCode),selected==null?mc.options.languageCode:selected.toComponent().getString());
        JsonArray languages=new JsonArray();manager.getLanguages().forEach((id,info)->{JsonObject row=new JsonObject();row.addProperty("id",id);row.addProperty("name",info.toComponent().getString());languages.add(row);});language.add("values",languages);options.add(language);
        for(var key:mc.options.keyMappings)options.add(base(key.getName(),Component.translatable(key.getName()).getString(),"controls","key",new JsonPrimitive(key.saveString()),key.getTranslatedKeyMessage().getString()));
        JsonObject out=new JsonObject();out.add("options",options);return out;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static JsonObject describe(Entry entry, Component caption, Function<Object,Component> formatter) {
        OptionInstance option=entry.option;Object value=option.get();
        if(!(caption.getContents() instanceof TranslatableContents translatable))throw new HttpError(409,"option caption is not translatable");
        String kind=value instanceof Boolean?"boolean":value instanceof Integer?"int":value instanceof Number?"double":"enum";
        JsonObject out=base(translatable.getKey(),caption.getString(),entry.group,kind,encode(value),formatter.apply(value).getString());
        if(!(value instanceof Boolean)) {
            if(option.values() instanceof OptionInstance.IntRange range){out.addProperty("min",range.minInclusive());out.addProperty("max",range.maxInclusive());}
            else if(option.values() instanceof OptionInstance.ClampingLazyMaxIntRange range){out.addProperty("min",range.minInclusive());out.addProperty("max",range.maxInclusive());}
            if(option.values() instanceof OptionInstance.SliderableValueSet slider)out.addProperty("slider",slider.toSliderValue(value));
            List<?> choices=choices(option);
            if(choices!=null){JsonArray values=new JsonArray();for(Object choice:choices){JsonObject row=new JsonObject();row.add("id",encode(choice));row.addProperty("name",formatter.apply(choice).getString());values.add(row);}out.add("values",values);}
        }
        return out;
    }
    private static List<?> choices(OptionInstance<?> option) {
        if(option.values() instanceof OptionInstance.Enum<?> values)return values.values();
        if(option.values() instanceof OptionInstance.SliderableEnum<?> values)return values.values();
        if(!(option.get() instanceof Number)&&option.values() instanceof OptionInstance.CycleableValueSet<?> cycle)return cycle.valueListSupplier().getSelectedList();
        return null;
    }
    static JsonElement encode(Object value) {
        if(value instanceof Boolean b)return new JsonPrimitive(b);
        if(value instanceof Number n)return new JsonPrimitive(n);
        if(value instanceof Enum<?> e)return new JsonPrimitive(e.name());
        return new JsonPrimitive(value.toString());
    }
    private static JsonObject base(String id,String name,String group,String kind,JsonElement value,String label){
        JsonObject out=new JsonObject();out.addProperty("id",id);out.addProperty("name",name);out.addProperty("group",group);out.addProperty("kind",kind);out.add("value",value);out.addProperty("label",label);return out;
    }
    static JsonObject write(Request r,Minecraft mc) {
        String id=r.reqStr("id");JsonElement value=r.body().get("value");
        if(value==null||!value.isJsonPrimitive())throw new HttpError(400,"value must be a JSON scalar");
        if(id.equals("language")){
            String code=string(value);var manager=mc.getLanguageManager();
            if(!manager.getLanguages().containsKey(code))throw new HttpError(400,"unknown language");
            boolean changed=!code.equals(manager.getSelected());
            if(changed){manager.setSelected(code);mc.options.languageCode=code;mc.reloadResourcePacks();}
            mc.options.save();JsonObject out=result(id,new JsonPrimitive(mc.options.languageCode));out.addProperty("reload_requested",changed);return out;
        }
        for(var key:mc.options.keyMappings)if(key.getName().equals(id)){
            InputConstants.Key next;
            try{next=InputConstants.getKey(string(value));}catch(IllegalArgumentException e){throw new HttpError(400,"invalid key save string");}
            key.setKey(next);KeyMapping.resetMapping();mc.options.save();return result(id,new JsonPrimitive(key.saveString()));
        }
        for(var part:net.minecraft.world.entity.player.PlayerModelPart.values())if(((TranslatableContents)part.getName().getContents()).getKey().equals(id)){
            if(!value.getAsJsonPrimitive().isBoolean())throw new HttpError(400,"value must be boolean");
            mc.options.setModelPart(part,value.getAsBoolean());mc.options.save();return result(id,new JsonPrimitive(mc.options.isModelPartEnabled(part)));
        }
        for(Entry entry:catalog(mc)){
            var caption=((OptionInstanceAccessor)(Object)entry.option).localapi$caption();
            if(caption.getContents() instanceof TranslatableContents text && text.getKey().equals(id)){
                apply(entry.option,value);
                // VideoSettingsScreen applies these changes when closing the screen.
                if(entry.option==mc.options.mipmapLevels()||entry.option==mc.options.textureFiltering()||entry.option==mc.options.maxAnisotropyBit()){
                    mc.updateMaxMipLevel(mc.options.mipmapLevels().get());mc.delayTextureReload();
                }
                if(id.equals("options.fullscreen.exclusive.mode"))mc.getWindow().changeFullscreenVideoMode();
                mc.options.save();return result(id,encode(entry.option.get()));
            }
        }
        throw new HttpError(404,"unknown option id");
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static void apply(OptionInstance option,JsonElement input) {
        Object old=option.get(), candidate;
        try {
            var value=input.getAsJsonPrimitive();
            if(old instanceof Boolean){if(!value.isBoolean())throw new IllegalArgumentException();candidate=value.getAsBoolean();}
            else if(old instanceof Integer){if(!value.isNumber())throw new IllegalArgumentException();candidate=value.getAsBigDecimal().intValueExact();}
            else if(old instanceof Double){if(!value.isNumber()||!Double.isFinite(value.getAsDouble()))throw new IllegalArgumentException();candidate=value.getAsDouble();}
            else if(old instanceof Enum<?> e){candidate=Enum.valueOf(e.getDeclaringClass(),string(input));}
            else if(old instanceof String)candidate=string(input);
            else throw new IllegalArgumentException();
            Optional validated=option.values().validateValue(candidate);
            if(validated.isEmpty())throw new IllegalArgumentException();
            candidate=validated.get();
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException e){throw new HttpError(400,"invalid option value");}
        option.set(candidate);
    }
    private static String string(JsonElement value){if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new HttpError(400,"value must be a string");return value.getAsString();}
    private static JsonObject result(String id,JsonElement value){JsonObject out=new JsonObject();out.addProperty("id",id);out.add("value",value);return out;}
}
