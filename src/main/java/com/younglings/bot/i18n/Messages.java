package com.younglings.bot.i18n;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The message catalogue: user-facing text looked up by key in the language the server chose, falling back to English and, for a key that
 * is in no catalogue at all, to the key itself (a test keeps that from happening). English lives in
 * {@code i18n/messages_en.properties}; a language pack is another {@code messages_<code>.properties} plus a line in
 * {@code i18n/languages.properties}. See docs/TRANSLATION.md. Placeholders are {@code {0}}, {@code {1}}, ... filled in order; there is
 * no other escaping, so apostrophes and braces in a message are plain text.
 */
@BService
public class Messages {
    private static final Logger log = LoggerFactory.getLogger(Messages.class);
    public static final String DEFAULT_LANGUAGE = "en";

    private final LanguageRepository languages;
    private final Map<String, Map<String, String>> catalogues;
    private final Map<String, String> names;
    private final ConcurrentHashMap<Long, String> chosen = new ConcurrentHashMap<>();

    public Messages(LanguageRepository languages) {
        this.languages = languages;
        this.names = loadNames();
        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        for (String code : names.keySet()) {
            Map<String, String> catalogue = loadCatalogue(code);
            if (catalogue == null) log.warn("Language '{}' is listed in languages.properties but has no messages file; skipped", code);
            else loaded.put(code, catalogue);
        }
        if (!loaded.containsKey(DEFAULT_LANGUAGE)) throw new IllegalStateException("The English catalogue (i18n/messages_en.properties) is missing");
        this.catalogues = Map.copyOf(loaded);
    }

    /** Code to its name in its own language ("en" to "English"), for the languages that have a catalogue. English first. */
    public Map<String, String> available() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(DEFAULT_LANGUAGE, names.getOrDefault(DEFAULT_LANGUAGE, "English"));
        catalogues.keySet().stream().filter(c -> !c.equals(DEFAULT_LANGUAGE)).sorted().forEach(c -> out.put(c, names.get(c)));
        return out;
    }

    public boolean isAvailable(String code) {
        return code != null && catalogues.containsKey(code);
    }

    /** The language a server chose, English when it chose none or chose one that is no longer available. */
    public String languageOf(long guildId) {
        String code = chosen.computeIfAbsent(guildId, id -> languages.find(id).orElse(DEFAULT_LANGUAGE));
        return isAvailable(code) ? code : DEFAULT_LANGUAGE;
    }

    /** Saves a server's language; the code must be one of {@link #available()}. */
    public void setLanguage(long guildId, String code) {
        if (!isAvailable(code)) throw new IllegalArgumentException("Unknown language: " + code);
        languages.save(guildId, code);
        chosen.put(guildId, code);
    }

    public String text(Guild guild, String key, Object... args) {
        return text(guild.getIdLong(), key, args);
    }

    public String text(long guildId, String key, Object... args) {
        return text(languageOf(guildId), key, args);
    }

    /** The message in a given language (English if that language has no such key), with {@code {0}}, {@code {1}}... filled in. */
    public String text(String language, String key, Object... args) {
        String template = catalogues.getOrDefault(language, Map.of()).get(key);
        if (template == null) template = catalogues.get(DEFAULT_LANGUAGE).get(key);
        if (template == null) {
            log.warn("No message for key '{}' in any language", key);
            return key;
        }
        return fill(template, args);
    }

    /** All keys of a language's catalogue, for the consistency test. */
    Map<String, String> catalogue(String language) {
        return catalogues.get(language);
    }

    static String fill(String template, Object... args) {
        String out = template;
        for (int i = 0; i < args.length; i++) out = out.replace("{" + i + "}", String.valueOf(args[i]));
        return out;
    }

    private static Map<String, String> loadNames() {
        Properties properties = read("i18n/languages.properties");
        Map<String, String> out = new LinkedHashMap<>();
        if (properties != null) properties.stringPropertyNames().stream().sorted().forEach(code -> out.put(code, properties.getProperty(code)));
        out.putIfAbsent(DEFAULT_LANGUAGE, "English");
        return out;
    }

    private static Map<String, String> loadCatalogue(String code) {
        Properties properties = read("i18n/messages_" + code + ".properties");
        if (properties == null) return null;
        Map<String, String> out = new LinkedHashMap<>();
        properties.stringPropertyNames().forEach(key -> out.put(key, properties.getProperty(key)));
        return Map.copyOf(out);
    }

    private static Properties read(String resource) {
        try (InputStream in = Messages.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return null;
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + resource, e);
        }
    }
}
