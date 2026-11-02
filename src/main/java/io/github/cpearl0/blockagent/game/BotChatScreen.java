package io.github.cpearl0.blockagent.game;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;

/** Ordinary chat, suggestions and local commands, without sending keystrokes to the player. */
final class BotChatScreen extends ChatScreen {
    private final Runnable stop;

    BotChatScreen(String draft, Runnable stop) {
        super(draft);
        this.stop = stop;
    }

    String draft() {
        return input == null ? "" : input.getValue();
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (!BotControl.isControlScreen(minecraft))
            return super.keyPressed(key, scanCode, modifiers);
        if (key == GLFW.GLFW_KEY_F8) {
            stop.run();
            return true;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) return true;
        return super.keyPressed(key, scanCode, modifiers);
    }

    @Override
    public void handleChatInput(String message, boolean addToHistory) {
        super.handleChatInput(message, addToHistory);
        if (input != null) input.setValue("");
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        if (BotControl.isControlScreen(minecraft))
            graphics.drawString(
                    font,
                    "BlockAgent running | F8: Stop | Chat or /blockagent",
                    4,
                    height - 26,
                    0xFFFFFF);
    }
}
