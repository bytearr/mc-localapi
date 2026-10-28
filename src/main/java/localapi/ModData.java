package localapi;

import com.google.gson.*;
import net.fabricmc.loader.api.ModContainer;
import java.nio.file.*;
import java.util.*;

final class ModData {
    static final int MAX_ICON_BYTES = 1 << 20;
    static JsonObject list(Collection<ModContainer> mods) {
        JsonArray entries = new JsonArray();
        mods.stream().sorted(Comparator.comparing(mod -> mod.getMetadata().getId())).forEach(mod -> {
            var m = mod.getMetadata(); JsonObject row = new JsonObject();
            row.addProperty("id", m.getId()); row.addProperty("name", m.getName());
            row.addProperty("version", m.getVersion().getFriendlyString()); row.addProperty("description", m.getDescription());
            JsonArray authors = new JsonArray(); m.getAuthors().forEach(person -> authors.add(person.getName())); row.add("authors", authors);
            row.addProperty("environment", m.getEnvironment().name().toLowerCase(Locale.ROOT));
            row.addProperty("has_icon", m.getIconPath(128).isPresent()); entries.add(row);
        });
        JsonObject out = new JsonObject(); out.add("mods", entries); return out;
    }
    static ItemIcons.Png icon(Request request, Collection<ModContainer> mods) {
        if (!request.method().equals("GET")) throw new HttpError(405, "mod icons need GET");
        String id = request.reqStr("id");
        var mod = mods.stream().filter(m -> m.getMetadata().getId().equals(id)).findFirst().orElseThrow(ModData::missing);
        String path = mod.getMetadata().getIconPath(128).orElseThrow(ModData::missing);
        // Only the metadata-selected relative path inside this mod, never an arbitrary file path.
        if (path.startsWith("/") || path.contains("\\") || Arrays.asList(path.split("/")).contains("..")) throw missing();
        try {
            Path file = mod.findPath(path).orElseThrow(ModData::missing).toRealPath();
            boolean inside = false;
            for (Path root : mod.getRootPaths()) if (file.startsWith(root.toRealPath())) { inside = true; break; }
            if (!inside || !Files.isRegularFile(file)) throw missing();
            try (var input = Files.newInputStream(file)) {
                byte[] bytes = input.readNBytes(MAX_ICON_BYTES + 1);
                if (!validPng(bytes)) throw missing();
                return new ItemIcons.Png(bytes);
            }
        } catch (java.io.IOException | java.nio.file.InvalidPathException e) { throw missing(); }
    }
    static boolean validPng(byte[] bytes) {
        byte[] signature = {(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a};
        if (bytes.length < signature.length || bytes.length > MAX_ICON_BYTES) return false;
        for (int i=0;i<signature.length;i++) if (bytes[i]!=signature[i]) return false;
        return true;
    }
    private static HttpError missing() { return new HttpError(404, "mod icon unavailable"); }
}
