package io.github.cpearl0.blockagent.game.mixin;

import io.github.cpearl0.blockagent.game.BotControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class ClientControlMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void blockagent$keepControlChat(Screen next, CallbackInfo callback) {
        if (BotControl.interceptScreen((Minecraft) (Object) this, next)) callback.cancel();
    }

    @Redirect(
            method = "setScreen",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lnet/minecraft/client/gui/screens/Screen;removed()V"))
    private void blockagent$preserveHiddenMenu(Screen screen) {
        if (!BotControl.hidingMenu(screen)) screen.removed();
    }

    @ModifyVariable(method = "openChatScreen", at = @At("HEAD"), argsOnly = true)
    private String blockagent$restoreChatDraft(String initial) {
        return BotControl.takeDraft(initial);
    }
}
