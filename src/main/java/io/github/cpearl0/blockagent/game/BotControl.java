package io.github.cpearl0.blockagent.game;

import io.github.cpearl0.blockagent.core.BotConfig;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/** Client-thread ownership of the player's controls; chat remains a separate input surface. */
public final class BotControl {
    private static final Map<KeyMapping, IKeyConflictContext> contexts = new IdentityHashMap<>();
    private static Object world, player;
    private static BotChatScreen chat;
    private static Screen menuScreen;
    private static Runnable stop = () -> {};
    private static boolean owned, changingScreen, blockedUntilIdle;
    private static volatile boolean autoResumeAfterDeath = true;
    private static String savedDraft = "";

    private BotControl() {}

    public static boolean isActive() {
        return owned;
    }

    public static boolean isControlScreen(Minecraft mc) {
        return owned && mc.screen == chat;
    }

    public static void configure(BotConfig.Survival survival) {
        autoResumeAfterDeath =
                survival == null
                        ? BotConfig.Survival.defaults().autoResumeAfterDeath()
                        : survival.autoResumeAfterDeath();
    }

    public static boolean ownsKey(KeyMapping key) {
        return owned && contexts.containsKey(key);
    }

    public interface KeyState {
        void blockagent$setKeyDown(boolean down);
    }

    /** Desired state, including toggle bindings, independent of the user's physical key events. */
    public static void setKeyDown(KeyMapping key, boolean down) {
        ((KeyState) key).blockagent$setKeyDown(down);
    }

    public static void tick(boolean running, Runnable stopAction) {
        Minecraft mc = Minecraft.getInstance();
        if (blockedUntilIdle) {
            if (!running) blockedUntilIdle = false;
            return;
        }
        if (owned && !samePlayer(mc)) {
            // Keep ownership across a same-dimension auto-resume death so a transient
            // DeathScreen/respawn overlay cannot hit the !owned stop path.
            if (mc.player != null && !mc.player.isAlive() && autoRespawn(mc)) return;
            if (resumableHere(mc) && mc.player != null && mc.player.isAlive()) adopt(mc);
            else if (resumableHere(mc)) {
                release(false);
                return;
            } else {
                stop.run();
                release(true);
                return;
            }
        }
        if (!running || mc.player == null || mc.level == null || !mc.player.isAlive()) {
            if (owned) {
                if (running && mc.player != null && !mc.player.isAlive() && autoRespawn(mc)) return;
                release(true);
            }
            return;
        }
        if (!owned) {
            if (mc.screen instanceof DeathScreen && autoRespawn(mc)) return;
            if (mc.screen != null
                    && !(mc.screen instanceof ChatScreen)
                    && !(mc.screen instanceof MenuAccess<?>)
                    && !(mc.screen instanceof PauseScreen)) {
                blockedUntilIdle = true;
                stopAction.run();
                return;
            }
            world = mc.level;
            player = mc.player;
            stop = stopAction;
            owned = true;
            String draft =
                    mc.screen instanceof ChatScreen
                                    && mc.screen.getFocused() instanceof EditBox input
                            ? input.getValue()
                            : takeDraft("");
            rememberMenu(mc.screen);
            chat = new BotChatScreen(draft, () -> stop.run());
            for (KeyMapping key :
                    new KeyMapping[] {
                        mc.options.keyUp,
                        mc.options.keyDown,
                        mc.options.keyLeft,
                        mc.options.keyRight,
                        mc.options.keyJump,
                        mc.options.keySprint,
                        mc.options.keyShift,
                        mc.options.keyUse
                    }) {
                contexts.put(key, key.getKeyConflictContext());
                key.setKeyConflictContext(KeyConflictContext.UNIVERSAL);
            }
            Navigation.stop(mc);
            setKeyDown(mc.options.keyShift, false);
            setKeyDown(mc.options.keyUse, false);
        }
        if (mc.screen != chat) {
            rememberMenu(mc.screen);
            show(mc, chat);
        }
    }

    /**
     * Preserve native menus without showing them over the user's chat or closing their server
     * state.
     */
    public static boolean interceptScreen(Minecraft mc, Screen next) {
        if (changingScreen) return false;
        // Keep the command alive across a normal-mode death. The host sends respawn.
        if (next instanceof DeathScreen && autoRespawn(mc)) return true;
        if (!owned || !samePlayer(mc)) return false;
        if (next == null
                || next instanceof ChatScreen
                || next instanceof PauseScreen
                || next instanceof MenuAccess<?>) {
            rememberMenu(next);
            return true;
        }
        // Disconnection and server confirmation screens must remain actionable.
        blockedUntilIdle = true;
        stop.run();
        release(false);
        return false;
    }

    /** Hiding an already visible container must not run its menu-removal side effects. */
    public static boolean hidingMenu(Screen old) {
        return owned
                && changingScreen
                && old == menuScreen
                && old instanceof MenuAccess<?> access
                && Minecraft.getInstance().player != null
                && access.getMenu() == Minecraft.getInstance().player.containerMenu;
    }

    /** Used only by the normal chat-opening path; drafts are never submitted automatically. */
    public static String takeDraft(String initial) {
        if (!initial.isEmpty() || savedDraft.isEmpty()) return initial;
        String draft = savedDraft;
        savedDraft = "";
        return draft;
    }

    public static void release(boolean restoreScreen) {
        if (!owned) return;
        Minecraft mc = Minecraft.getInstance();
        boolean same = samePlayer(mc);
        String draft = chat == null ? "" : chat.draft();
        Screen restore =
                menuScreen instanceof MenuAccess<?> access
                                && same
                                && access.getMenu() == mc.player.containerMenu
                        ? menuScreen
                        : null;
        owned = false;
        Navigation.stop(mc);
        setKeyDown(mc.options.keyShift, false);
        setKeyDown(mc.options.keyUse, false);
        contexts.forEach(KeyMapping::setKeyConflictContext);
        contexts.clear();
        if (restoreScreen && mc.screen == chat) {
            if (restore != null) {
                savedDraft = draft;
                show(mc, restore);
            } else show(mc, draft.isEmpty() ? null : new ChatScreen(draft));
        }
        chat = null;
        menuScreen = null;
        world = null;
        player = null;
        stop = () -> {};
    }

    private static boolean samePlayer(Minecraft mc) {
        return mc.level == world && mc.player == player && mc.player != null && mc.player.isAlive();
    }

    private static void adopt(Minecraft mc) {
        world = mc.level;
        player = mc.player;
    }

    /** Same-dimension, non-hardcore death while auto-resume is on. The command is not a stop. */
    private static boolean resumableHere(Minecraft mc) {
        return autoRespawn(mc)
                && world instanceof ClientLevel previous
                && previous.dimension().equals(mc.level.dimension());
    }

    private static boolean autoRespawn(Minecraft mc) {
        return autoResumeAfterDeath
                && mc.player != null
                && mc.level != null
                && !mc.level.getLevelData().isHardcore();
    }

    private static void rememberMenu(Screen screen) {
        if (screen instanceof MenuAccess<?>) menuScreen = screen;
    }

    private static void show(Minecraft mc, Screen screen) {
        changingScreen = true;
        try {
            mc.setScreen(screen);
        } finally {
            changingScreen = false;
        }
    }
}
