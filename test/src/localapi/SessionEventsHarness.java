package localapi;

import com.google.gson.JsonObject;
import localapi.api.StreamSink;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class SessionEventsHarness {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    private static Request request(String... args) {
        Map<String,String> query = new HashMap<>();
        for (int i=0;i<args.length;i+=2) query.put(args[i],args[i+1]);
        return new Request("GET", "/journal", query, new JsonObject());
    }
    private static void error(int status, Runnable action) {
        try { action.run(); throw new AssertionError("missing error"); }
        catch (HttpError e) { check(e.status==status, "expected HTTP "+status); }
    }
    private static final class Sink implements StreamSink {
        final List<JsonObject> events = new ArrayList<>();
        boolean open=true;
        Runnable closer=()->{};
        public boolean send(JsonObject e) { if(!open)return false; events.add(e.deepCopy());return true; }
        public boolean isOpen(){return open;}
        public void close(){open=false;closer.run();}
        public void onClose(Runnable r){closer=r;if(!open)r.run();}
    }
    private static Object field(Object object, String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);
    }
    private static void finiteStream() throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/finite",exchange->{
            if(!StreamConnection.tryAcquire(8))throw new AssertionError("slot unavailable");
            var sink=new StreamConnection();
            JsonObject frame=new JsonObject();frame.addProperty("type","finite");
            sink.send(frame);sink.close(); // A plugin may finish synchronously before HTTP headers start.
            try { sink.start(exchange,"/finite"); }
            catch(java.io.IOException error){StreamConnection.release();exchange.close();throw error;}
        });
        server.start();
        try(var client=java.net.http.HttpClient.newHttpClient()) {
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/finite")).timeout(java.time.Duration.ofSeconds(3)).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            check(response.statusCode()==200 && response.body().contains("finite"),"synchronously closed plugin streams still drain queued frames");
        } finally { server.stop(0); }
    }

    private static void http() throws Exception {
        Config config = new Config(); config.port=0; config.maxStreams=1;
        ApiServer server = new ApiServer(config);
        var running = new java.util.concurrent.atomic.AtomicBoolean(true);
        var busy = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread game = new Thread(() -> {
            while (running.get()) {
                GameQueue.drain();
                if (busy.get()) { SessionEvents.INSTANCE.invalidate("/world"); SessionEvents.INSTANCE.flush(); }
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
            }
        }, "session-test-game");
        game.setDaemon(true); game.start();
        var readerWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
        java.io.InputStream body=null;
        var client=java.net.http.HttpClient.newHttpClient();
        try {
            server.start();
            String base="http://127.0.0.1:"+field(server,"boundPort");
            var unauth=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/events")).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            check(unauth.statusCode()==401,"stream requires authentication");
            GameQueue.submit(()->{SessionEvents.INSTANCE.begin("minecraft:overworld");return null;}).get(2,java.util.concurrent.TimeUnit.SECONDS);
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/events")).header("X-Password",config.password).build(),java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            body=response.body();
            check(response.statusCode()==200 && response.headers().firstValue("Content-Type").orElse("").contains("ndjson"),"real authenticated NDJSON response");
            var reader=new java.io.BufferedReader(new java.io.InputStreamReader(body,java.nio.charset.StandardCharsets.UTF_8));
            check(com.google.gson.JsonParser.parseString(reader.readLine()).getAsJsonObject().get("resync").getAsBoolean(),"hello always demands fresh state");
            check(com.google.gson.JsonParser.parseString(reader.readLine()).getAsJsonObject().get("type").getAsString().equals("resync"),"opening cursor is first queued frame");
            var excess=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/events")).header("X-Password",config.password).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            check(excess.statusCode()==429,"core stream shares configured stream limit");
            busy.set(true);
            var heartbeat=readerWorker.submit(()->{
                while(true){
                    String line=reader.readLine();if(line==null)throw new AssertionError("stream ended");
                    var event=com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                    if(event.get("type").getAsString().equals("heartbeat"))return event;
                }
            }).get(9,java.util.concurrent.TimeUnit.SECONDS);
            check(heartbeat.has("dropped") && heartbeat.get("frames").getAsLong()>100,"heartbeat not starved by continuous traffic");
            busy.set(false);
            var journal=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/journal")).header("X-Password",config.password).build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            check(journal.statusCode()==200 && com.google.gson.JsonParser.parseString(journal.body()).getAsJsonObject().getAsJsonObject("data").getAsJsonArray("events").size()==1,"live invalidations absent from HTTP journal");
        } finally {
            busy.set(false);
            if(body!=null)body.close();
            server.stop();
            client.shutdownNow();
            running.set(false);game.interrupt();game.join(2000);
            readerWorker.shutdownNow();
        }
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
        while(StreamConnection.active()!=0 && System.nanoTime()<deadline)Thread.sleep(10);
        check(StreamConnection.active()==0,"HTTP shutdown releases active stream");
    }

    public static void main(String[] args) throws Exception {
        var hub=new SessionEvents();
        check(hub.journal(request()).getAsJsonArray("events").isEmpty(), "empty before session");
        var a=new Sink();hub.subscribe(a);
        check(a.events.get(0).get("type").getAsString().equals("resync"), "opening requires fresh snapshot");
        hub.begin("minecraft:overworld");
        String session=hub.journal(request()).get("session").getAsString();
        check(hub.active(),"session active");
        hub.invalidate("/inventory", "/inventory", "/player");hub.flush();
        var invalidation=a.events.getLast();
        check(invalidation.getAsJsonArray("paths").size()==2,"coalesced paths");
        check(!invalidation.has("data") && !invalidation.has("event"),"no state snapshot in invalidation");
        check(hub.journal(request()).getAsJsonArray("events").size()==1,"state changes not journalled");
        var b=new Sink();hub.subscribe(b);
        long cursor=b.events.getFirst().get("seq").getAsLong();
        check(cursor==a.events.getLast().get("seq").getAsLong(),"new subscriber does not create a false gap");
        JsonObject chat=new JsonObject();chat.addProperty("text","hello");chat.addProperty("signature","sig");
        hub.record("chat_received",chat);chat.addProperty("text","mutated");
        hub.record("chat_deletion_requested",chat);
        check(a.events.getLast().get("seq").getAsLong()==b.events.getLast().get("seq").getAsLong(),"published ordering common to subscribers");
        var page=hub.journal(request("limit","2"));
        check(page.get("has_more").getAsBoolean() && page.get("next_since").getAsInt()==2,"pagination cursor");
        var fact=page.getAsJsonArray("events").get(1).getAsJsonObject();
        check(fact.getAsJsonObject("data").get("text").getAsString().equals("hello"),"receipt remains unchanged after deletion/input mutation");
        check(fact.get("time").getAsLong()>0,"receipt timestamp present");
        fact.getAsJsonObject("data").addProperty("text","external mutation");
        check(hub.journal(request()).getAsJsonArray("events").get(1).getAsJsonObject().getAsJsonObject("data").get("text").getAsString().equals("hello"),"journal response detached");
        var rest=hub.journal(request("session",session,"since","2"));
        check(rest.getAsJsonArray("events").size()==1 && !rest.get("has_more").getAsBoolean(),"next page exact");
        error(400,()->hub.journal(request("since","1")));
        error(400,()->hub.journal(request("limit","0")));
        error(400,()->hub.journal(request("since","-1")));
        error(400,()->hub.journal(request("limit","1001")));
        error(409,()->hub.journal(request("session","wrong")));
        error(409,()->hub.journal(request("session",session,"since","100")));
        a.close();int before=a.events.size();hub.record("death",new JsonObject());
        check(a.events.size()==before,"closed subscriber removed");
        hub.end();int ended=hub.journal(request()).get("last_id").getAsInt();hub.end();
        check(!hub.active() && hub.journal(request()).get("last_id").getAsInt()==ended,"disconnect idempotent and history readable");
        hub.record("chat_received",chat);
        check(hub.journal(request()).get("last_id").getAsInt()==ended,"outside-session receipt ignored");
        hub.begin("minecraft:overworld");
        check(!hub.journal(request()).get("session").getAsString().equals(session),"new session identity");
        check(hub.journal(request()).getAsJsonArray("events").size()==1,"old history cleared on new session");
        error(409,()->hub.journal(request("session",session,"since","1")));

        var life = new SessionEvents();
        life.begin("minecraft:overworld");
        life.death("minecraft:overworld", 1, 64, -2, "Player fell from a high place");
        life.death("minecraft:overworld", 1, 64, -2, "duplicate");
        check(life.journal(request()).get("last_id").getAsInt()==2,"repeated death notifications deduplicated per life");
        var death=life.journal(request()).getAsJsonArray("events").get(1).getAsJsonObject().getAsJsonObject("data");
        check(death.get("cause").getAsString().equals("Player fell from a high place"),"server death message retained");
        check(death.getAsJsonArray("position").get(2).getAsDouble()==-2 && death.get("dimension").getAsString().equals("minecraft:overworld"),"death location retained");
        life.respawn("minecraft:overworld");
        check(life.journal(request()).get("last_id").getAsInt()==2,"same-dimension respawn is not a dimension transition");
        life.death("minecraft:overworld", 3, 80, 5, null);
        check(life.journal(request()).getAsJsonArray("events").get(2).getAsJsonObject().getAsJsonObject("data").get("cause").isJsonNull(),"unknown cause explicit and new life may die again");
        life.respawn("minecraft:the_nether");
        var transition=life.journal(request()).getAsJsonArray("events").get(3).getAsJsonObject();
        check(transition.get("kind").getAsString().equals("dimension_changed") && transition.getAsJsonObject("data").get("from").getAsString().equals("minecraft:overworld") && transition.getAsJsonObject("data").get("to").getAsString().equals("minecraft:the_nether"),"actual from/to transition retained");
        life.respawn("minecraft:the_nether");
        check(life.journal(request()).get("last_id").getAsInt()==4,"duplicate dimension notification ignored");
        life.end();life.begin("minecraft:the_end");
        check(life.journal(request()).get("last_id").getAsInt()==1,"reconnect is not a cross-server dimension transition");

        // An unstarted real HTTP sink models a reader that consumes no frames at all.
        StreamConnection slow=new StreamConnection();hub.subscribe(slow);
        for(int i=0;i<800;i++) { hub.record("chat_received",chat);hub.invalidate("/inventory");hub.flush(); }
        @SuppressWarnings("unchecked") var queue=(ArrayBlockingQueue<String>)field(slow,"queue");
        long drops=((AtomicLong)field(slow,"dropped")).get();
        check(queue.size()==512 && drops>0,"bounded queue drops instead of blocking producer");
        var first=com.google.gson.JsonParser.parseString(queue.peek()).getAsJsonObject();
        check(first.get("seq").getAsLong()>1,"sequence exposes lost frames");
        check(hub.journal(request()).get("last_id").getAsInt()==801,"slow stream never loses historical records");
        slow.close();
        check(!slow.send(chat),"closed sink rejects sends");
        var reconnect=new Sink();hub.subscribe(reconnect);
        check(reconnect.events.size()==1 && reconnect.events.getFirst().get("type").getAsString().equals("resync"),"reconnect never replays stale state");
        check(reconnect.events.getFirst().get("journal_last_id").getAsInt()==801,"history cursor independent of stream sequence");
        check(Dispatcher.isActiveStream("/events/"),"core stream normalized");
        check(Dispatcher.openStream(new Request("POST","/events",Map.of(),new JsonObject()),new Sink()).status()==405,"stream GET only");
        check(Dispatcher.openStream(new Request("GET","/events",Map.of("since","1"),new JsonObject()),new Sink()).status()==400,"state replay rejected");
        finiteStream();
        http();
        System.out.println("PASS "+checks+" session/stream checks");
    }
}
