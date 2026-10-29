package targethud;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/** Minecraft 26.3: read the actual local breaking state without a core accessor or plugin mixin. */
final class MiningProgress {
    private final VarHandle position;
    private final VarHandle progress;
    private final VarHandle item;

    MiningProgress() throws ReflectiveOperationException {
        var lookup = MethodHandles.privateLookupIn(MultiPlayerGameMode.class, MethodHandles.lookup());
        position = lookup.findVarHandle(MultiPlayerGameMode.class, "destroyBlockPos", BlockPos.class);
        progress = lookup.findVarHandle(MultiPlayerGameMode.class, "destroyProgress", float.class);
        item = lookup.findVarHandle(MultiPlayerGameMode.class, "destroyingItem", ItemStack.class);
    }

    float read(MultiPlayerGameMode mode, BlockPos target, ItemStack held, boolean creative) {
        if (creative || mode == null || !mode.isDestroying() || !target.equals(position.get(mode))) return -1;
        ItemStack startedWith = (ItemStack) item.get(mode);
        if (startedWith == null || !ItemStack.isSameItemSameComponents(startedWith, held)) return -1;
        float value = (float) progress.get(mode);
        return Float.isFinite(value) ? Math.clamp(value, 0f, 1f) : -1;
    }
}
