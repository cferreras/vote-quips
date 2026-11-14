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

    /**
     * Una web de votación de {@code votar.webs}.
     *
     * @param name     nombre que se muestra en {web} (por defecto, el texto del enlace sin colores ni corchetes)
     * @param services nombres de servicio que envía la web a Votifier (si está vacío, se deduce de la url y el nombre)
     */
    public record VoteSite(String linkText, String hoverText, String url, String name, List<String> services) {
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

    /**
     * Nombre que se muestra para el servicio con el que llega un voto. Busca primero en {@code servicio} de cada web
     * y luego compara con la url y el nombre; si no encuentra ninguna, devuelve el propio nombre del servicio.
     */
    public String siteName(String serviceName) {
        String service = serviceName == null ? "" : serviceName.trim();
        if (service.isEmpty()) return vote.getOrDefault("web_desconocida", "una web de votación");
        for (VoteSite site : voteSites) {
            for (String s : site.services()) {
                if (s.equalsIgnoreCase(service)) return site.name();
            }
        }
        String key = simplify(service);
        if (!key.isEmpty()) {
            for (VoteSite site : voteSites) {
                if (!site.services().isEmpty()) continue;
                String host = simplify(host(site.url()));
                String name = simplify(site.name());
                if ((!host.isEmpty() && (host.contains(key) || key.contains(host)))
                        || (!name.isEmpty() && (name.contains(key) || key.contains(name)))) {
                    return site.name();
                }
            }
        }
        return service;
    }

    private static String host(String url) {
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            if (host == null) return "";
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            int dot = host.lastIndexOf('.');
            return dot > 0 ? host.substring(0, dot) : host; // sin dominio de primer nivel
        } catch (Exception e) {
            return "";
        }
    }

    /** Minúsculas y solo letras y números, para comparar nombres de servicio con urls y nombres. */
    private static String simplify(String text) {
        return text.toLowerCase(java.util.Locale.ROOT).replaceAll("§.", "").replaceAll("[^a-z0-9]", "");
    }

    /** Texto del enlace sin códigos de color ni corchetes, para usarlo como nombre por defecto. */
    private static String plainName(String linkText) {
        return linkText.replaceAll("§.", "").replaceAll("[\\[\\]]", "").trim();
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
                String path = "votar.webs[" + i + "]";
                Map<String, String> site = stringMap(items.get(i), path);
                String linkText = site.getOrDefault("texto_enlace", "");
                String name = site.getOrDefault("nombre", plainName(linkText));
                Object servicio = items.get(i) instanceof Map<?, ?> raw ? raw.get("servicio") : null;
                sites.add(new VoteSite(linkText, site.getOrDefault("texto_hover", ""), site.getOrDefault("url", ""),
                        name, services(servicio, path)));
            }
            return List.copyOf(sites);
        }
        if (map.get("url") != null) {
            String linkText = vote.getOrDefault("texto_enlace", "");
            return List.of(new VoteSite(linkText, vote.getOrDefault("texto_hover", ""), vote.get("url"),
                    plainName(linkText), List.of()));
        }
        return defaults != null ? defaults.voteSites : List.of();
    }

    /** {@code servicio}: un texto o una lista de textos. */
    private static List<String> services(Object value, String path) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) result.add(String.valueOf(item).trim());
            }
            return List.copyOf(result);
        }
        if (value instanceof Map<?, ?>) {
            throw new IllegalArgumentException(path + ".servicio debe ser un texto o una lista");
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? List.of() : List.of(text);
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
