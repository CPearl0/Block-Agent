package io.github.cpearl0.blockagent.game.mixin;

import io.github.cpearl0.blockagent.game.BotControl;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.ToggleKeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Toggle bindings override isDown; use their parent's owned-state read during bot control. */
@Mixin(ToggleKeyMapping.class)
abstract class ClientToggleKeyMappingMixin extends KeyMapping {
    protected ClientToggleKeyMappingMixin(String name, int keyCode, String category) {
        super(name, keyCode, category);
    }

    @Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
    private void blockagent$readOwnedState(CallbackInfoReturnable<Boolean> callback) {
        if (BotControl.ownsKey(this)) callback.setReturnValue(super.isDown());
    }
}
