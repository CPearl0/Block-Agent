package io.github.cpearl0.blockagent.game.mixin;

import io.github.cpearl0.blockagent.game.ServerEvidence;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelEvidenceMixin {
    @Inject(method = "handleBlockChangedAck", at = @At("RETURN"))
    private void blockagent$acknowledged(int sequence, CallbackInfo ci) {
        ServerEvidence.acknowledged((ClientLevel) (Object) this, sequence);
    }
}
