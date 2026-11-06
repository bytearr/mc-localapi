package localapi;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.ScissorState;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import localapi.mixin.ModelManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.render.GuiItemAtlas;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Fresh inventory-renderer output, never a texture file server or an icon cache. */
final class ItemIcons {
    static final int SIZE = 32;
    private static final AtomicInteger pending = new AtomicInteger();
    record Png(byte[] bytes) {}

    static ItemStack stack(Request r, Minecraft mc) {
        if (r.has("id") == r.has("slot")) throw new HttpError(400, "provide exactly one of id or slot");
        ItemStack stack;
        if (r.has("id")) {
            Identifier id = Identifier.tryParse(r.reqStr("id"));
            if (id == null) throw new HttpError(400, "invalid item id");
            if (!BuiltInRegistries.ITEM.containsKey(id)) throw new HttpError(404, "unknown item id");
            stack = BuiltInRegistries.ITEM.getValue(id).getDefaultInstance();
        } else {
            if (mc.player == null || mc.level == null) throw new HttpError(409, "not in a world");
            var menu = r.bool("container", false) ? mc.player.containerMenu : mc.player.inventoryMenu;
            double slot = r.reqDbl("slot");
            if (slot != Math.rint(slot) || slot < 0 || slot >= menu.slots.size()) throw new HttpError(400, r.bool("container", false) ? "invalid container slot" : "invalid inventory slot");
            stack = menu.getSlot((int)slot).getItem();
        }
        if (stack.isEmpty()) throw new HttpError(404, "empty item stack");
        return stack.copy();
    }

    static CompletableFuture<Png> read(Request r, Minecraft mc) {
        if (!r.method().equals("GET")) throw new HttpError(405, "item icons need GET");
        ItemStack stack = stack(r, mc);
        var model = stack.get(DataComponents.ITEM_MODEL);
        if (model == null || !((ModelManagerAccessor)mc.getModelManager()).localapi$itemModels().containsKey(model))
            throw new HttpError(404, "item model is not loaded");
        if (pending.incrementAndGet() > 2) { pending.decrementAndGet(); throw new HttpError(429, "too many pending icon reads"); }
        var result = new CompletableFuture<Png>();
        GuiItemAtlas atlas = null;
        GpuBuffer buffer = null;
        var projection = RenderSystem.getProjectionMatrixBuffer();
        var projectionType = RenderSystem.getProjectionType();
        var lights = RenderSystem.getShaderLights();
        var scissor = new ScissorState(RenderSystem.getScissorStateForRenderTypeDraws());
        boolean queued = false;
        try {
            // Same GUI model resolution and atlas renderer as inventory icons. One fresh slot per request.
            stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false);
            var state = new TrackingItemStackRenderState();
            mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.GUI, mc.level, mc.player, 0);
            if (state.isEmpty()) throw new HttpError(404, "item has no drawable GUI model");
            atlas = new GuiItemAtlas(mc.gameRenderer.featureRenderDispatcher(), SIZE, SIZE);
            var slot = atlas.getOrUpdate(state);
            if (slot == null) throw new HttpError(409, "item renderer unavailable");
            var device = RenderSystem.getDevice();
            buffer = device.createBuffer(() -> "Local API item icon", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, SIZE * SIZE * 4L);
            final var ownedAtlas = atlas; final var ownedBuffer = buffer;
            device.createCommandEncoder().copyTextureToBuffer(slot.textureView().texture(), buffer, 0L, () -> {
                try (ownedAtlas; ownedBuffer; var mapped = ownedBuffer.map(true, false)) {
                    result.complete(new Png(encode(mapped.data(), SIZE, SIZE)));
                } catch (Throwable error) { result.completeExceptionally(error); }
                finally { pending.decrementAndGet(); }
            }, 0);
            queued = true;
            return result;
        } finally {
            RenderSystem.setProjectionMatrix(projection, projectionType);
            RenderSystem.setShaderLights(lights);
            RenderSystem.getScissorStateForRenderTypeDraws().setFrom(scissor);
            if (!queued) {
                try { if (buffer != null) buffer.close(); }
                finally { try { if (atlas != null) atlas.close(); } finally { pending.decrementAndGet(); } }
            }
        }
    }

    static byte[] encode(java.nio.ByteBuffer rgba, int width, int height) throws java.io.IOException {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y=0;y<height;y++) for(int x=0;x<width;x++) {
            int offset=(x+y*width)*4;
            int r=rgba.get(offset)&255,g=rgba.get(offset+1)&255,b=rgba.get(offset+2)&255,a=rgba.get(offset+3)&255;
            // Vanilla blits this atlas with PREMULTIPLIED_ALPHA; PNG requires straight alpha.
            if (a == 0) r = g = b = 0;
            else if (a < 255) { r = Math.min(255, (r*255+a/2)/a); g = Math.min(255, (g*255+a/2)/a); b = Math.min(255, (b*255+a/2)/a); }
            image.setRGB(x,height-y-1,(a<<24)|(r<<16)|(g<<8)|b);
        }
        var bytes = new java.io.ByteArrayOutputStream();
        if (!javax.imageio.ImageIO.write(image,"png",bytes)) throw new java.io.IOException("PNG encoder unavailable");
        return bytes.toByteArray();
    }
}
