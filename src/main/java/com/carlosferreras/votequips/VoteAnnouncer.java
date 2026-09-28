package com.carlosferreras.votequips;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Agrupa los votos seguidos, elige la frase y la anuncia. Todos los métodos se llaman desde el hilo del servidor.
 */
public class VoteAnnouncer {

    static final long GROUP_WINDOW_MS = 15_000;
    static final long MAX_WAIT_MS = 30_000;
    static final long RECENT_DEATH_MS = 2 * 60_000;
    static final long RECENT_JOIN_MS = 60_000;
    static final int RECENT_PHRASES = 10;

    static final String MURIO_RECIENTE = "murio_reciente";
    static final String PRIMER_VOTO = "primer_voto";
    static final String RECIEN_CONECTADO = "recien_conectado";
    static final String MADRUGADA = "madrugada";
    static final String GENERICO = "generico";
    static final String DOBLE = "doble";
    static final String CADENA = "cadena";

    private static final Pattern VARIABLE = Pattern.compile("\\{(\\w+)}");

    /** Voto con las categorías que cumplía el jugador en el momento de votar, por orden de prioridad. */
    private record PendingVote(String username, List<String> categories, Map<String, String> vars) {
    }

    private final QuipsData data;
    private final PlayerTracker tracker;
    private QuipsConfig config;

    private final List<PendingVote> pending = new ArrayList<>();
    private long firstVoteMs;
    private long lastVoteMs;

    public VoteAnnouncer(QuipsConfig config, QuipsData data, PlayerTracker tracker) {
        this.config = config;
        this.data = data;
        this.tracker = tracker;
    }

    static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }

    public QuipsConfig getConfig() {
        return config;
    }

    public void setConfig(QuipsConfig config) {
        this.config = config;
    }

    public void onVote(MinecraftServer server, String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        username = username.trim();
        ServerPlayer online = server.getPlayerList().getPlayerByName(username);
        if (online != null) {
            username = online.getPlainTextName(); // mayúsculas correctas
        }

        // El contexto se evalúa al llegar el voto, no al enviar el anuncio.
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("jugador", username);
        List<String> categories = new ArrayList<>();

        PlayerTracker.Death death = tracker.recentDeath(username, RECENT_DEATH_MS);
        if (death != null) {
            categories.add(MURIO_RECIENTE);
            vars.put("causa", config.cause(death.causeKey()));
        }
        if (data.registerVoter(username)) {
            categories.add(PRIMER_VOTO);
        }
        if (online != null) {
            long connected = tracker.connectedMs(online);
            if (connected >= 0 && connected < RECENT_JOIN_MS) {
                categories.add(RECIEN_CONECTADO);
            }
        }
        int hour = LocalTime.now().getHour();
        if (hour >= 2 && hour < 6) {
            categories.add(MADRUGADA);
        }
        categories.add(GENERICO);
        data.save();

        long now = nowMs();
        if (pending.isEmpty()) {
            firstVoteMs = now;
        }
        lastVoteMs = now;
        pending.add(new PendingVote(username, categories, vars));
    }

    public void tick(MinecraftServer server) {
        if (pending.isEmpty()) {
            return;
        }
        long now = nowMs();
        if (now - lastVoteMs >= GROUP_WINDOW_MS || now - firstVoteMs >= MAX_WAIT_MS) {
            flush(server);
        }
    }

    /** Envía el anuncio de los votos pendientes. */
    public void flush(MinecraftServer server) {
        if (pending.isEmpty()) {
            return;
        }
        // Jugadores distintos (sin distinguir mayúsculas), en orden de llegada.
        Map<String, PendingVote> byPlayer = new LinkedHashMap<>();
        for (PendingVote vote : pending) {
            byPlayer.putIfAbsent(PlayerTracker.normalize(vote.username()), vote);
        }
        List<PendingVote> players = new ArrayList<>(byPlayer.values());

        String type;
        String category;
        Map<String, String> vars;
        if (players.size() == 1) {
            PendingVote vote = players.get(0);
            type = "individual";
            category = vote.categories().stream()
                    .filter(c -> !config.phrases(c).isEmpty())
                    .findFirst().orElse(GENERICO);
            vars = new LinkedHashMap<>(vote.vars());
        } else if (players.size() == 2) {
            type = DOBLE;
            category = DOBLE;
            vars = new LinkedHashMap<>();
            vars.put("j1", players.get(0).username());
            vars.put("j2", players.get(1).username());
        } else {
            type = CADENA;
            category = CADENA;
            vars = new LinkedHashMap<>();
            vars.put("n", String.valueOf(pending.size()));
        }
        pending.clear();

        vars.put("frase", fill(pickPhrase(category), vars));
        String message = fill(config.format(type), vars);
        server.getPlayerList().broadcastSystemMessage(Component.literal(message), false);
        data.save();
    }

    /** Elige una frase que no esté entre las últimas usadas; si no queda ninguna, la usada hace más tiempo. */
    private String pickPhrase(String category) {
        List<String> phrases = config.phrases(category);
        if (phrases.isEmpty()) {
            return "";
        }
        List<String> recent = data.recent(category);
        List<String> candidates = phrases.stream().filter(p -> !recent.contains(p)).distinct().toList();
        String chosen;
        if (!candidates.isEmpty()) {
            chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        } else {
            chosen = recent.stream().filter(phrases::contains).findFirst().orElse(phrases.get(0));
        }
        recent.remove(chosen);
        recent.add(chosen);
        while (recent.size() > RECENT_PHRASES) {
            recent.remove(0);
        }
        return chosen;
    }

    /** Sustituye {variable} en una sola pasada; las variables desconocidas se dejan tal cual. */
    private static String fill(String template, Map<String, String> vars) {
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = vars.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
