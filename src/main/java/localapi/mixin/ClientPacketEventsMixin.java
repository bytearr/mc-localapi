package localapi.mixin;

import localapi.ClientEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.MessageSignatureCache;
import net.minecraft.network.protocol.game.ClientboundDeleteChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** TAIL runs only after vanilla's game-thread packet dispatch and successful handling. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketEventsMixin {
    @Shadow private MessageSignatureCache messageSignatureCache;

    @Inject(method = "handlePlayerCombatKill", at = @At("TAIL"))
    private void localapi$death(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        ClientEvents.death(Minecraft.getInstance(), packet.playerId(), packet.message() == null ? null : packet.message().getString());
    }

    @Inject(method = "handleRespawn", at = @At("TAIL"))
    private void localapi$respawn(CallbackInfo ci) { ClientEvents.respawn(Minecraft.getInstance()); }

    @Inject(method = "handleDeleteChat", at = @At("TAIL"))
    private void localapi$deleteChat(ClientboundDeleteChatPacket packet, CallbackInfo ci) {
        packet.messageSignature().unpack(messageSignatureCache).ifPresent(ClientEvents::chatDeleted);
    }

    @Inject(method = {"handleContainerSetSlot", "handleSetCursorItem", "handleSetPlayerInventory",
            "handleContainerContent", "handleContainerSetData", "handleMerchantOffers", "handleSetEquipment"}, at = @At("TAIL"))
    private void localapi$inventory(CallbackInfo ci) { ClientEvents.invalidate("/inventory", "/container", "/hand", "/player", "/screen"); }

    @Inject(method = {"handleOpenScreen", "handleMountScreenOpen", "handleContainerClose"}, at = @At("TAIL"))
    private void localapi$screen(CallbackInfo ci) { ClientEvents.invalidate("/screen", "/container", "/state"); }

    @Inject(method = {"handleSetHealth", "handleSetExperience", "handleUpdateMobEffect", "handleRemoveMobEffect", "handlePlayerAbilities", "handleUpdateAttributes"}, at = @At("TAIL"))
    private void localapi$player(CallbackInfo ci) { ClientEvents.invalidate("/player"); }

    @Inject(method = {"handleAddObjective", "handleSetScore", "handleResetScore", "handleSetDisplayObjective", "handleSetPlayerTeamPacket"}, at = @At("TAIL"))
    private void localapi$scoreboard(CallbackInfo ci) { ClientEvents.invalidate("/scoreboard"); }

    @Inject(method = "handleBossUpdate", at = @At("TAIL"))
    private void localapi$bossbars(CallbackInfo ci) { ClientEvents.invalidate("/bossbars"); }

    @Inject(method = "handleUpdateAdvancementsPacket", at = @At("TAIL"))
    private void localapi$advancements(CallbackInfo ci) { ClientEvents.invalidate("/advancements"); }

    @Inject(method = "handleAwardStats", at = @At("TAIL"))
    private void localapi$stats(CallbackInfo ci) {
        localapi.StatsSync.awarded((ClientPacketListener)(Object)this);
        ClientEvents.invalidate("/stats");
    }

    @Inject(method = {"handleBlockUpdate", "handleChunkBlocksUpdate", "handleLevelChunkWithLight", "handleForgetLevelChunk", "handleLightUpdatePacket", "applyLightData", "handleChunksBiomes", "handleBlockChangedAck"}, at = @At("TAIL"))
    private void localapi$blocks(CallbackInfo ci) { ClientEvents.invalidate("/block", "/blocks", "/environment", "/target"); }

    @Inject(method = {"handlePlayerInfoUpdate", "handlePlayerInfoRemove"}, at = @At("TAIL"))
    private void localapi$players(CallbackInfo ci) { ClientEvents.invalidate("/players"); }
}
