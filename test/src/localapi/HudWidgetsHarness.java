package localapi;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class HudWidgetsHarness {
    static int checks;
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); checks++; }
    static void error(int code, Runnable action) { try { action.run(); throw new AssertionError("expected " + code); } catch(HttpError e) { check(e.status == code, e.getMessage()); } }
    static Request req(String json) { return new Request("POST", "/hud", Map.of(), JsonParser.parseString(json).getAsJsonObject()); }
    static Request owner(String token) { var r=req("{}"); r.body().addProperty("owner",token); return r; }
    static Request widget(String token, String id, String type, double value) {
        var r=owner(token); r.body().addProperty("id",id); r.body().addProperty("type",type);
        r.body().addProperty("label","Test"); r.body().addProperty("value",value); return r;
    }
    static String create(HudWidgets h) { return h.create(req("{}")).get("owner").getAsString(); }
    public static void main(String[] args) {
        var clock=new AtomicLong(); var h=new HudWidgets(clock::get); String a=create(h), b=create(h);
        check(!a.equals(b),"unique owner tokens");
        var first=widget(a,"same","counter",1); h.put(first); h.put(widget(b,"same","counter",2));
        check(h.list(owner(a)).getAsJsonArray("widgets").size()==1,"owner-scoped inspection");
        check(h.list(owner(b)).getAsJsonArray("widgets").get(0).getAsJsonObject().get("value").getAsInt()==2,"same ID isolated");
        first.body().addProperty("value",3);h.put(first);check(h.visible().size()==2,"update replaces");
        for(int i=0;i<1000;i++){first.body().addProperty("value",i);h.put(first);}
        check(h.visible().size()==2 && h.visible().get(0).value()==999,"rapid updates bounded and latest wins");
        first.body().addProperty("width",1);error(400,()->h.put(first));
        check(h.visible().get(0).value()==999,"invalid replacement atomic");
        first.body().addProperty("width",180);
        h.remove(first);check(h.visible().size()==1,"only selected owner's widget removed");
        check(!h.remove(first).get("removed").getAsBoolean(),"repeated remove harmless");
        error(404,()->h.list(owner("unknown")));error(404,()->h.renew(owner("unknown")));
        var timer=widget(a,"timer","timer",10);timer.body().addProperty("running",true);h.put(timer);
        clock.set(2_500_000_000L);
        check(h.visible().get(0).valueAt(clock.get())==7.5,"monotonic countdown");
        check(h.visible().get(0).text(clock.get()).equals("Test: 0:08"),"countdown rounds upward");
        timer.body().addProperty("running",false);h.put(timer);clock.set(4_000_000_000L);
        check(h.visible().get(0).valueAt(clock.get())==10,"paused timer stays script supplied");
        h.release(owner(a));check(h.visible().size()==1,"release removes all owned widgets");
        error(404,()->h.put(timer));
        clock.set(10_000_000_000L);check(h.visible().isEmpty(),"crashed owner expires at deadline without disconnect");
        error(404,()->h.renew(owner(b)));
        a=create(h);var shortWidget=widget(a,"short","counter",1);shortWidget.body().addProperty("ttl_ms",100);h.put(shortWidget);
        clock.addAndGet(100_000_000L);check(h.visible().isEmpty(),"widget TTL exact boundary");
        check(h.list(owner(a)).get("lease_remaining_ms").getAsLong()>0,"widget expiry keeps owner");
        h.put(widget(a,"long","counter",1));clock.addAndGet(9_000_000_000L);h.renew(owner(a));
        clock.addAndGet(1_000_000_000L);check(h.visible().isEmpty(),"owner renewal does not renew widgets");
        check(h.list(owner(a)).get("lease_remaining_ms").getAsLong()==9000,"lease renewed from current time");
        h.put(widget(a,"again","counter",1));clock.addAndGet(9_000_000_000L);
        check(h.visible().isEmpty(),"widget updates do not renew owner");
        for(String json:List.of("{\"ttl_ms\":0}","{\"ttl_ms\":30001}","{\"ttl_ms\":1.5}","{\"ttl_ms\":\"NaN\"}"))error(400,()->h.create(req(json)));
        String token=create(h);
        for(String type:List.of("unknown","counter","progress","timer")) {
            var r=widget(token,"bad",type,-1.5);error(400,()->h.put(r));
        }
        var invalid=widget(token,"bad","progress",101);error(400,()->h.put(invalid));
        invalid.body().addProperty("max",0);error(400,()->h.put(invalid));
        invalid.body().addProperty("max",200);invalid.body().addProperty("label","bad\nline");error(400,()->h.put(invalid));
        invalid.body().addProperty("label","bad\u00a7a");error(400,()->h.put(invalid));
        invalid.body().addProperty("label","x".repeat(129));error(400,()->h.put(invalid));
        h.clear();check(h.visible().isEmpty(),"session reset clears widgets");String expiredToken=token;error(404,()->h.list(owner(expiredToken)));
        token=create(h);for(int i=0;i<HudWidgets.MAX_PER_OWNER;i++)h.put(widget(token,"w"+i,"counter",i));
        var excess=widget(token,"overflow","counter",1);error(429,()->h.put(excess));
        h.put(widget(token,"w0","counter",99));check(h.visible().size()==16,"replacement allowed at owner capacity");
        for(int o=1;o<4;o++){String t=create(h);for(int i=0;i<16;i++)h.put(widget(t,"w"+i,"counter",i));}
        String fifth=create(h);error(429,()->h.put(widget(fifth,"overflow","counter",1)));
        check(h.visible().size()==64,"global widget cap");
        for(int i=5;i<16;i++)create(h);error(429,()->create(h));
        clock.addAndGet(10_000_000_000L);check(h.visible().isEmpty(),"expiry frees global capacity");create(h);
        h.clear();String t=create(h);var layout=widget(t,"layout","progress",50);layout.body().addProperty("x",10000);layout.body().addProperty("y",10000);h.put(layout);
        for(int[] size:List.of(new int[]{1920,1080},new int[]{960,540},new int[]{320,180},new int[]{10,10},new int[]{0,0})) {
            var box=HudWidgets.layout(h.visible().get(0),size[0],size[1]);
            check(box.x()>=0 && box.y()>=0 && box.x()+box.width()<=size[0] && box.y()+box.height()<=size[1],"layout bounded at GUI scale/resize");
        }
        System.out.println("PASS "+checks+" HUD-widget checks");
    }
}
