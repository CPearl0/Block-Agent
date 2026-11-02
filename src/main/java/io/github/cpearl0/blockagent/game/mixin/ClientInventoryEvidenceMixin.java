package io.github.cpearl0.blockagent.game.mixin;

import io.github.cpearl0.blockagent.game.ServerEvidence;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientInventoryEvidenceMixin {
    @Inject(method = "handleContainerContent", at = @At("RETURN"))
    private void blockagent$content(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        ServerEvidence.contentUpdated(
                ((ClientPacketListener) (Object) this).getLevel(), packet.getContainerId());
    }

    @Inject(method = "handleContainerSetSlot", at = @At("RETURN"))
    private void blockagent$slot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        ServerEvidence.inventoryUpdated(((ClientPacketListener) (Object) this).getLevel());
    }
}
