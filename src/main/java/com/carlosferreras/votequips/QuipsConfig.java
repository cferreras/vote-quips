package com.carlosferreras.votequips;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Formatos, textos de causas de muerte y frases por categoría, leídos de frases.yml.
 */
public record QuipsConfig(Map<String, String> formats, Map<String, String> causes, Map<String, String> vote,
                          List<VoteSite> voteSites, Map<String, List<String>> phrases) {

    private static final String DEFAULT_RESOURCE = "/frases.yml";

    /** Una web de votación de {@code votar.webs}. */
    public record VoteSite(String linkText, String hoverText, String url) {
    }

    public String format(String type) {
        return formats.getOrDefault(type, "{frase}");
    }

    public String cause(String damageType) {
        String cause = causes.get(damageType);
        if (cause == null) cause = causes.get("default");
        return cause != null ? cause : damageType;
    }

    /** Valor de texto de la sección {@code votar} (mensaje, separador). */
    public String vote(String key) {
        return vote.getOrDefault(key, "");
    }

    public List<String> phrases(String category) {
        return phrases.getOrDefault(category, List.of());
    }

    /**
     * Carga el archivo (creándolo con los valores por defecto si no existe). Si hay un error, devuelve
     * {@code fallback} o, si es null, la configuración por defecto incluida en el mod.
     */
    public static QuipsConfig load(Path file, QuipsConfig fallback) {
        try {
            return loadStrict(file);
        } catch (Exception e) {
            VoteQuips.LOGGER.error("Error leyendo {}; se mantiene la configuración anterior", file, e);
            return fallback != null ? fallback : loadDefaults();
        }
    }

    /** Como {@link #load}, pero lanza la excepción en lugar de usar una configuración de respaldo. */
    public static QuipsConfig loadStrict(Path file) throws Exception {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            try (InputStream in = QuipsConfig.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                Files.copy(in, file);
            }
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            QuipsConfig config = parse(reader, loadDefaults());
            VoteQuips.LOGGER.info("Cargadas frases de {} categorías desde {}", config.phrases.size(), file);
            return config;
        }
    }

    private static QuipsConfig loadDefaults() {
        try (Reader reader = new InputStreamReader(QuipsConfig.class.getResourceAsStream(DEFAULT_RESOURCE), StandardCharsets.UTF_8)) {
            return parse(reader, null);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + DEFAULT_RESOURCE + " del mod", e);
        }
    }

    /** Las claves que falten en {@code formato}, {@code causas} y {@code votar} se completan con {@code defaults}. */
    private static QuipsConfig parse(Reader reader, QuipsConfig defaults) {
        Object root = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("El archivo debe contener un mapa en la raíz");
        }

        Map<String, String> formats = new HashMap<>(defaults != null ? defaults.formats : Map.of());
        formats.putAll(stringMap(map.get("formato"), "formato"));
        Map<String, String> causes = new HashMap<>(defaults != null ? defaults.causes : Map.of());
        causes.putAll(stringMap(map.get("causas"), "causas"));
        Map<String, String> vote = new HashMap<>(defaults != null ? defaults.vote : Map.of());
        vote.putAll(stringMap(map.get("votar"), "votar"));
        vote.remove("webs");
        List<VoteSite> voteSites = voteSites(map.get("votar"), vote, defaults);

        Map<String, List<String>> phrases = new HashMap<>();
        if (map.get("frases") instanceof Map<?, ?> categories) {
            categories.forEach((category, list) -> {
                if (!(list instanceof List<?> items)) {
                    throw new IllegalArgumentException("frases." + category + " debe ser una lista");
                }
                List<String> texts = new ArrayList<>();
                for (Object item : items) {
                    if (item != null) texts.add(String.valueOf(item));
                }
                phrases.put(String.valueOf(category), List.copyOf(texts));
            });
        } else {
            throw new IllegalArgumentException("Falta la sección 'frases'");
        }
        return new QuipsConfig(Map.copyOf(formats), Map.copyOf(causes), Map.copyOf(vote), voteSites,
                Map.copyOf(phrases));
    }

    /**
     * Webs de {@code votar.webs}. Si no hay lista pero sí {@code votar.url} (formato antiguo de una sola web),
     * se usa esa; si no hay ninguna de las dos, las de {@code defaults}.
     */
    private static List<VoteSite> voteSites(Object section, Map<String, String> vote, QuipsConfig defaults) {
        Map<?, ?> map = section instanceof Map<?, ?> m ? m : Map.of();
        Object webs = map.get("webs");
        if (webs != null) {
            if (!(webs instanceof List<?> items)) {
                throw new IllegalArgumentException("votar.webs debe ser una lista");
            }
            List<VoteSite> sites = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                Map<String, String> site = stringMap(items.get(i), "votar.webs[" + i + "]");
                sites.add(new VoteSite(site.getOrDefault("texto_enlace", ""), site.getOrDefault("texto_hover", ""),
                        site.getOrDefault("url", "")));
            }
            return List.copyOf(sites);
        }
        if (map.get("url") != null) {
            return List.of(new VoteSite(vote.getOrDefault("texto_enlace", ""), vote.getOrDefault("texto_hover", ""),
                    vote.get("url")));
        }
        return defaults != null ? defaults.voteSites : List.of();
    }

    private static Map<String, String> stringMap(Object value, String section) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("'" + section + "' debe ser un mapa");
        }
        Map<String, String> result = new HashMap<>();
        map.forEach((k, v) -> {
            if (v != null) result.put(String.valueOf(k), String.valueOf(v));
        });
        return result;
    }
}
