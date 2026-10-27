package localapi;

import com.google.gson.*;
import localapi.mixin.EditBoxAccessor;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class ScreenWidgetsHarness {
    static int checks;
    static void check(boolean b,String message){if(!b)throw new AssertionError(message);checks++;}
    static void error(int status,Runnable action){try{action.run();throw new AssertionError("missing error");}catch(HttpError e){check(e.status==status,e.getMessage());}}
    static final Font FONT=new Font(null){
        public int width(String text){return text.length();}
        public String plainSubstrByWidth(String text,int width){return text.substring(0,Math.min(text.length(),Math.max(width,0)));}
        public String plainSubstrByWidth(String text,int width,boolean backwards){return plainSubstrByWidth(text,width);}
    };
    static final class TestScreen extends Screen {
        Runnable focus=()->{};
        TestScreen(){super(null,FONT,Component.literal("test"));width=320;height=240;}
        void add(AbstractWidget widget){addRenderableWidget(widget);}
        void remove(AbstractWidget widget){removeWidget(widget);}
        public void setFocused(net.minecraft.client.gui.components.events.GuiEventListener widget){
            // Exercise vanilla focus state without invoking the absent native IME/window bridge.
            Map<EditBox,Boolean> editable=new IdentityHashMap<>();
            if(getFocused() instanceof EditBox box)editable.put(box,(boolean)field(box,"isEditable"));
            if(widget instanceof EditBox box)editable.put(box,(boolean)field(box,"isEditable"));
            try {
                for(var box:editable.keySet())setField(box,"isEditable",false);
                super.setFocused(widget);
            } finally { editable.forEach((box,value)->setField(box,"isEditable",value)); }
            focus.run();
        }
    }
    static Object field(EditBox box,String name){try{var f=EditBox.class.getDeclaredField(name);f.setAccessible(true);return f.get(box);}catch(Exception e){throw new AssertionError(e);}}
    static void setField(EditBox box,String name,Object value){try{var f=EditBox.class.getDeclaredField(name);f.setAccessible(true);f.set(box,value);}catch(Exception e){throw new AssertionError(e);}}
    static ScreenWidgets tracker(){return new ScreenWidgets(box->new EditBoxAccessor(){
        public int localapi$maxLength(){return (int)field(box,"maxLength");}
        public boolean localapi$isEditable(){return (boolean)field(box,"isEditable");}
    });}
    static JsonObject view(ScreenWidgets tracker,Screen screen){
        JsonObject out=new JsonObject();JsonArray widgets=new JsonArray();
        for(var child:screen.children())if(child instanceof AbstractWidget)widgets.add(new JsonObject());
        tracker.describe(screen,out,widgets);out.add("widgets",widgets);return out;
    }
    static Request request(JsonObject view,int index,String text){
        JsonObject body=new JsonObject();body.add("revision",view.get("revision"));
        body.add("ref",view.getAsJsonArray("widgets").get(index).getAsJsonObject().get("ref"));
        if(text!=null)body.addProperty("text",text);
        return new Request("POST",text==null?"/screen/activate":"/screen/text",Map.of(),body);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        var tracker=tracker();var screen=new TestScreen();var current=new AtomicReference<Screen>(screen);var clicks=new AtomicInteger();
        Button button=Button.builder(Component.literal("Click"),b->clicks.incrementAndGet()).build();screen.add(button);
        var first=view(tracker,screen);var req=request(first,0,null);
        check(first.getAsJsonArray("widgets").get(0).getAsJsonObject().get("supported").getAsBoolean(),"vanilla button supported");
        check(first.get("revision").equals(view(tracker,screen).get("revision")),"unchanged inspection keeps revision");
        current.set(new TestScreen());error(409,()->tracker.perform(req,current::get,false));check(clicks.get()==0,"replacement screen never clicked");
        current.set(screen);tracker.reset();error(409,()->tracker.perform(req,current::get,false));
        final var activeReq=request(view(tracker,screen),0,null);
        button.active=false;error(409,()->tracker.perform(activeReq,current::get,false));button.active=true;
        button.visible=false;error(409,()->tracker.perform(activeReq,current::get,false));button.visible=true;
        screen.focus=()->current.set(new TestScreen());error(409,()->tracker.perform(activeReq,current::get,false));check(clicks.get()==0,"focus-induced screen replacement never clicked");
        screen.focus=()->{};current.set(screen);
        var response=tracker.perform(activeReq,current::get,false);
        check(clicks.get()==1 && button.isFocused(),"normal callback once and focus applied");
        check(!response.get("screen_changed").getAsBoolean(),"unchanged screen result");
        error(409,()->tracker.perform(activeReq,current::get,false));check(clicks.get()==1,"reference consumed after success");
        var resized=request(view(tracker,screen),0,null);screen.width++;error(409,()->tracker.perform(resized,current::get,false));
        var removed=request(view(tracker,screen),0,null);screen.remove(button);screen.add(Button.builder(Component.literal("replacement"),b->clicks.addAndGet(100)).build());
        error(409,()->tracker.perform(removed,current::get,false));check(clicks.get()==1,"same-index replacement never clicked");
        var throwing=new TestScreen();current.set(throwing);throwing.add(Button.builder(Component.literal("throw"),b->{clicks.incrementAndGet();throw new IllegalStateException("callback");}).build());
        var throwReq=request(view(tracker,throwing),0,null);
        try{tracker.perform(throwReq,current::get,false);throw new AssertionError("expected callback error");}catch(IllegalStateException expected){check(clicks.get()==2,"throwing callback invoked once");}
        error(409,()->tracker.perform(throwReq,current::get,false));
        var textScreen=new TestScreen();current.set(textScreen);EditBox edit=new EditBox(FONT,0,0,100,20,Component.literal("text"));edit.setMaxLength(8);textScreen.add(edit);
        var textView=view(tracker,textScreen);var textReq=request(textView,0,"valid");var changes=new AtomicInteger();edit.setResponder(s->changes.incrementAndGet());
        edit.setEditable(false);error(409,()->tracker.perform(textReq,current::get,true));edit.setEditable(true);
        error(400,()->tracker.perform(request(textView,0,"too long!"),current::get,true));
        error(400,()->tracker.perform(request(textView,0,"bad\n"),current::get,true));
        error(400,()->tracker.perform(request(textView,0,"\uD800"),current::get,true));
        check(edit.getValue().isEmpty() && changes.get()==0,"invalid text never partially edits or calls responder");
        var set=tracker.perform(textReq,current::get,true);
        check(edit.getValue().equals("valid") && changes.get()==1 && edit.isFocused(),"normal text edit and responder");
        check(set.get("accepted").getAsBoolean(),"accepted result");
        error(409,()->tracker.perform(textReq,current::get,true));
        tracker.perform(request(view(tracker,textScreen),0,""),current::get,true);check(edit.getValue().isEmpty(),"empty replacement clears field");
        tracker.perform(request(view(tracker,textScreen),0,"12345678"),current::get,true);check(edit.getValue().length()==8,"exact length accepted");
        var malformed=request(view(tracker,textScreen),0,"ok");malformed.body().addProperty("text",7);error(400,()->tracker.perform(malformed,current::get,true));
        error(400,()->tracker.perform(request(view(tracker,textScreen),0,null),current::get,false));
        var custom=new EditBox(FONT,Component.literal("custom")){};textScreen.add(custom);
        var unsupported=view(tracker,textScreen);check(!unsupported.getAsJsonArray("widgets").get(1).getAsJsonObject().get("supported").getAsBoolean(),"custom text control explicitly unsupported");
        error(400,()->tracker.perform(request(unsupported,1,"x"),current::get,true));
        var changed=new TestScreen();current.set(changed);
        changed.add(Button.builder(Component.literal("open"),b->current.set(new TestScreen())).build());
        check(tracker.perform(request(view(tracker,changed),0,null),current::get,false).get("screen_changed").getAsBoolean(),"normal activation may intentionally change screen");
        current.set(textScreen);
        edit.setResponder(value->{if(value.equals("reject"))edit.setValue("fixed");});
        var validated=tracker.perform(request(view(tracker,textScreen),0,"reject"),current::get,true);
        check(!validated.get("accepted").getAsBoolean() && validated.get("value").getAsString().equals("fixed"),"responder validation preserved and result reported honestly");
        var blocked=request(view(tracker,textScreen),0,"ok");
        error(409,()->tracker.perform(blocked,()->null,true));
        var changedLength=request(view(tracker,textScreen),0,"length");
        textScreen.focus=()->setField(edit,"maxLength",2);
        error(400,()->tracker.perform(changedLength,current::get,true));
        check(edit.getValue().equals("fixed"),"max length revalidated after focus callback");
        System.out.println("PASS "+checks+" screen-widget checks");
    }
}
