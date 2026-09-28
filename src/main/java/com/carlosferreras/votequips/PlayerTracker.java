package com.carlosferreras.votequips;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;

/**
 * Recuerda cuándo se conectó cada jugador y su última muerte. Solo se usa desde el hilo del servidor.
 */
public class PlayerTracker {

    public record Death(String causeKey, long timeMs) {
    }

    private final Map<UUID, Long> joinTimes = new HashMap<>();
    private final Map<String, Death> deaths = new HashMap<>();

    public void onJoin(ServerPlayer player) {
        joinTimes.put(player.getUUID(), VoteAnnouncer.nowMs());
    }

    public void onLeave(ServerPlayer player) {
        joinTimes.remove(player.getUUID());
    }

    public void onDeath(ServerPlayer player, DamageSource source) {
        String key = source.typeHolder().getRegisteredName();
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        deaths.put(normalize(player.getPlainTextName()), new Death(key, VoteAnnouncer.nowMs()));
    }

    /** Última muerte del jugador si ocurrió hace menos de {@code maxAgeMs}. */
    public Death recentDeath(String username, long maxAgeMs) {
        String name = normalize(username);
        Death death = deaths.get(name);
        if (death == null) {
            return null;
        }
        if (VoteAnnouncer.nowMs() - death.timeMs() >= maxAgeMs) {
            deaths.remove(name);
            return null;
        }
        return death;
    }

    /** Milisegundos que lleva conectado, o -1 si no está conectado. */
    public long connectedMs(ServerPlayer player) {
        Long joined = joinTimes.get(player.getUUID());
        return joined == null ? -1 : VoteAnnouncer.nowMs() - joined;
    }

    public static String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
