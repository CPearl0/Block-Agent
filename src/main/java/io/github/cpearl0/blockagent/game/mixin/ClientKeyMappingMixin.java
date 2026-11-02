package io.github.cpearl0.blockagent.game.mixin;

import io.github.cpearl0.blockagent.game.BotControl;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
abstract class ClientKeyMappingMixin implements BotControl.KeyState {
    @Shadow protected boolean isDown;

    @Override
    public void blockagent$setKeyDown(boolean down) {
        isDown = down;
    }

    @Inject(method = "setDown", at = @At("HEAD"), cancellable = true)
    private void blockagent$keepOwnedState(boolean down, CallbackInfo callback) {
        if (BotControl.ownsKey((KeyMapping) (Object) this)) callback.cancel();
    }

    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void blockagent$readOwnedState(CallbackInfoReturnable<Boolean> callback) {
        if (BotControl.ownsKey((KeyMapping) (Object) this)) callback.setReturnValue(isDown);
    }
}
