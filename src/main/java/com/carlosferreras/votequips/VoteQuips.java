package com.carlosferreras.votequips;

import com.vexsoftware.votifier.fabric.event.VoteListener;
import java.nio.file.Path;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoteQuips implements DedicatedServerModInitializer {

    public static final String MOD_ID = "vote-quips";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private volatile MinecraftServer server;

    @Override
    public void onInitializeServer() {
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        Path configFile = configDir.resolve("frases.yml");

        QuipsData data = QuipsData.load(configDir.resolve("datos.json"));
        PlayerTracker tracker = new PlayerTracker();
        VoteAnnouncer announcer = new VoteAnnouncer(QuipsConfig.load(configFile, null), data, tracker);

        ServerLifecycleEvents.SERVER_STARTING.register(s -> this.server = s);
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            announcer.flush(s);
            data.save();
            this.server = null;
        });
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((s, resourceManager, success) ->
                announcer.setConfig(QuipsConfig.load(configFile, announcer.getConfig())));
        ServerTickEvents.END_SERVER_TICK.register(announcer::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                VoteCommand.register(dispatcher, announcer::getConfig, announcer::setConfig, configFile));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> tracker.onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> tracker.onLeave(handler.getPlayer()));
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer player) {
                tracker.onDeath(player, source);
            }
        });

        // El evento puede dispararse fuera del hilo principal: todo el trabajo se pasa al hilo del servidor.
        VoteListener.EVENT.register(vote -> {
            String username = vote.getUsername();
            MinecraftServer current = this.server;
            if (current == null) {
                LOGGER.warn("Voto de {} recibido sin servidor activo; se ignora", username);
                return;
            }
            current.execute(() -> announcer.onVote(current, username));
        });
    }
}
