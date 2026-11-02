package io.github.cpearl0.blockagent;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.cpearl0.blockagent.bridge.LocalBridge;
import io.github.cpearl0.blockagent.core.AgentController;
import io.github.cpearl0.blockagent.game.BotControl;
import io.github.cpearl0.blockagent.game.GameBridge;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Client commands are consumed locally; no server installation or custom payload is required. */
@Mod(value = BlockAgentMod.ID, dist = Dist.CLIENT)
public final class BlockAgentMod {
    public static final String ID = "blockagent";
    private static final Logger LOG = LoggerFactory.getLogger(ID);
    private final GameBridge game = new GameBridge();
    private final KeyMapping stopKey =
            new KeyMapping(
                    "key.blockagent.stop",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_F8,
                    "key.categories.blockagent");
    private volatile AgentController agent;
    private LocalBridge bridge;

    public BlockAgentMod(IEventBus modBus) {
        modBus.addListener(this::registerKeys);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
        NeoForge.EVENT_BUS.addListener(this::tick);
        NeoForge.EVENT_BUS.addListener(this::logout);
        NeoForge.EVENT_BUS.addListener(this::shutdown);
        reload();
        try {
            bridge =
                    LocalBridge.startIfEnabled(
                            game,
                            () -> agent == null ? new com.google.gson.JsonObject() : agent.status(),
                            this::command,
                            FMLPaths.GAMEDIR.get().resolve("blockagent"));
        } catch (Exception ex) {
            LOG.error(
                    "Could not start explicitly enabled development bridge: {}",
                    ex.getClass().getSimpleName());
        }
        LOG.info(
                "BlockAgent client initialized. Commands: /blockagent <task>, /blockagent stop, /blockagent status");
    }

    private synchronized boolean reload() {
        if (bridge != null) bridge.cancelCurrent();
        try {
            if (agent != null) {
                agent.reload();
                return true;
            }
            agent =
                    new AgentController(
                            FMLPaths.CONFIGDIR.get().resolve("blockagent-client.json"),
                            FMLPaths.GAMEDIR.get().resolve("blockagent/memory"),
                            game,
                            this::feedback);
            return true;
        } catch (Exception ex) {
            feedback(
                    "Failed to load configuration ("
                            + ex.getClass().getSimpleName()
                            + "). Check config/blockagent-client.json and run /blockagent reload.");
            return false;
        }
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(stopKey);
    }

    private void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher()
                .register(
                        Commands.literal("blockagent")
                                .executes(
                                        context -> {
                                            help();
                                            return 1;
                                        })
                                .then(
                                        Commands.argument(
                                                        "instruction",
                                                        StringArgumentType.greedyString())
                                                .executes(
                                                        context -> {
                                                            command(
                                                                    StringArgumentType.getString(
                                                                            context,
                                                                            "instruction"));
                                                            return 1;
                                                        })));
    }

    private void command(String input) {
        String text = input.strip();
        if (text.isEmpty() || text.equals("help")) {
            help();
            return;
        }
        if (text.equals("reload")) {
            if (reload()) feedback("Configuration reloaded.");
            return;
        }
        if (text.equals("stop")) {
            if (bridge != null) bridge.cancelCurrent();
            if (agent != null) agent.stop();
            else game.cancel();
            return;
        }
        AgentController current = agent;
        if (current == null) {
            feedback(
                    "Configuration is not loaded. Update config/blockagent-client.json and run /blockagent reload.");
            return;
        }
        if (text.equals("status")) {
            feedback(current.status().toString());
            return;
        }
        if (bridge != null) bridge.cancelCurrent();
        current.submit(text);
    }

    private void tick(ClientTickEvent.Post event) {
        AgentController current = agent;
        BotControl.tick(current != null && current.isBusy(), () -> command("stop"));
        while (stopKey.consumeClick()) command("stop");
        game.tick();
        if (current != null) current.tick();
    }

    private void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (bridge != null) bridge.cancelCurrent();
        if (agent != null) agent.stop();
        game.cancel();
        BotControl.release(false);
    }

    private void shutdown(GameShuttingDownEvent event) {
        if (bridge != null) bridge.close();
        if (agent != null) agent.close();
        game.cancel();
        BotControl.release(false);
    }

    private void help() {
        feedback(
                "/blockagent <task>: start or replace a task; stop or F8: stop; status: show status; reload: reload configuration. Controls the current player.");
    }

    private void feedback(String text) {
        LOG.info("{}", text);
        Minecraft.getInstance()
                .execute(
                        () ->
                                Minecraft.getInstance()
                                        .gui
                                        .getChat()
                                        .addMessage(Component.literal("[BlockAgent] " + text)));
    }
}
