package com.carlosferreras.votequips;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Datos que sobreviven a los reinicios: quién ha votado alguna vez y las últimas frases usadas por categoría.
 */
public class QuipsData {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static class Stored {
        Set<String> votantes = new HashSet<>();
        Map<String, List<String>> recientes = new HashMap<>();
    }

    private final Path file;
    private Stored stored = new Stored();

    private QuipsData(Path file) {
        this.file = file;
    }

    public static QuipsData load(Path file) {
        QuipsData data = new QuipsData(file);
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Stored stored = GSON.fromJson(reader, Stored.class);
                if (stored != null) {
                    if (stored.votantes == null) stored.votantes = new HashSet<>();
                    if (stored.recientes == null) stored.recientes = new HashMap<>();
                    data.stored = stored;
                }
            } catch (Exception e) {
                VoteQuips.LOGGER.error("No se pudo leer {}; se empieza con datos vacíos", file, e);
            }
        }
        return data;
    }

    /** Registra el voto y devuelve true si era el primero de este jugador. */
    public boolean registerVoter(String username) {
        return stored.votantes.add(PlayerTracker.normalize(username));
    }

    /** Frases usadas recientemente en la categoría, de la más antigua a la más reciente (modificable). */
    public List<String> recent(String category) {
        return stored.recientes.computeIfAbsent(category, k -> new ArrayList<>());
    }

    public void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(stored), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            VoteQuips.LOGGER.error("No se pudo guardar {}", file, e);
        }
    }
}
