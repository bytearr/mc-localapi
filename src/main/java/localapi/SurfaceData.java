package localapi;

import com.google.gson.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

final class SurfaceData {
    static final int MAX_COLUMNS = 65536;
    static long columns(int x1, int x2, int z1, int z2) {
        // Chunk coordinates must map to block coordinates without overflow.
        if (Math.min(x1,x2) < -134217728 || Math.max(x1,x2) > 134217727 || Math.min(z1,z2) < -134217728 || Math.max(z1,z2) > 134217727)
            throw new HttpError(400, "chunk coordinate out of range");
        long width = Math.abs((long)x2-x1)+1, depth = Math.abs((long)z2-z1)+1;
        if (width > MAX_COLUMNS/256 || depth > MAX_COLUMNS/256 || width*depth > MAX_COLUMNS/256)
            throw new HttpError(400, "surface rectangle exceeds " + MAX_COLUMNS + " columns");
        return width*depth*256;
    }
    static JsonObject read(ClientLevel level, Request r) {
        int x1=r.reqInt("chunk_x1"), x2=r.reqInt("chunk_x2"), z1=r.reqInt("chunk_z1"), z2=r.reqInt("chunk_z2");
        long requested=columns(x1,x2,z1,z2);
        var palette=new BlockPalette(); JsonArray rows=new JsonArray(), missing=new JsonArray(), empty=new JsonArray();
        var pos=new BlockPos.MutableBlockPos();
        for(int cx=Math.min(x1,x2);cx<=Math.max(x1,x2);cx++) for(int cz=Math.min(z1,z2);cz<=Math.max(z1,z2);cz++) {
            var chunk=LiveData.loadedChunk(level,cx<<4,cz<<4);
            if(chunk==null){JsonArray p=new JsonArray();p.add(cx);p.add(cz);missing.add(p);continue;}
            for(int dx=0;dx<16;dx++) for(int dz=0;dz<16;dz++) {
                int x=(cx<<4)+dx,z=(cz<<4)+dz;
                // WORLD_SURFACE is the highest non-air block (including fluid), maintained by the loaded chunk.
                int y=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,dx,dz);
                if(y<level.getMinY()){JsonArray p=new JsonArray();p.add(x);p.add(z);empty.add(p);continue;}
                pos.set(x,y,z);var state=chunk.getBlockState(pos);
                if(state.isAir()) throw new HttpError(409,"loaded chunk surface heightmap is not ready");
                int index=palette.add(state,level,pos);
                JsonArray row=new JsonArray();row.add(x);row.add(z);row.add(y);row.add(index);rows.add(row);
            }
        }
        JsonObject out=new JsonObject();palette.write(out);out.add("columns",rows);out.add("unloaded_chunks",missing);out.add("empty_columns",empty);
        out.addProperty("requested_columns",requested);out.addProperty("max_columns",MAX_COLUMNS);return out;
    }
}
