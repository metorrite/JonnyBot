package com.younglings.bot.i18n;

import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The message catalogue: user-facing text looked up by key in the language the server chose, falling back to English and, for a key that
 * is in no catalogue at all, to the key itself (a test keeps that from happening). English lives in
 * {@code i18n/messages_en.properties}; a language pack is another {@code messages_<code>.properties} plus a line in
 * {@code i18n/languages.properties}. See docs/TRANSLATION.md. Placeholders are {@code {0}}, {@code {1}}, ... filled in order; there is
 * no other escaping, so apostrophes and braces in a message are plain text.
 * <p>
 * The packs listed under {@code drafts=} in languages.properties are machine-drafted (T-099): they are offered on the Language page only
 * while {@code LANGUAGE_PACKS_ENABLED=true}, and may lack keys (English shows for those).
 */
@BService
public class Messages {
    private static final Logger log = LoggerFactory.getLogger(Messages.class);
    public static final String DEFAULT_LANGUAGE = "en";

    private final LanguageRepository languages;
    private final Map<String, Map<String, String>> catalogues;
    private final Map<String, String> names;
    private final Set<String> drafts;
    private final boolean packsEnabled;
    private final ConcurrentHashMap<Long, String> chosen = new ConcurrentHashMap<>();

    public Messages(LanguageRepository languages, BotConfig config) {
        this.languages = languages;
        this.packsEnabled = config.getLanguagePacksEnabled();
        Properties listed = read("i18n/languages.properties");
        this.names = loadNames(listed);
        this.drafts = drafts(listed);
        Map<String, Map<String, String>> loaded = new LinkedHashMap<>();
        for (String code : names.keySet()) {
            Map<String, String> catalogue = loadCatalogue(code);
            if (catalogue == null) log.warn("Language '{}' is listed in languages.properties but has no messages file; skipped", code);
            else loaded.put(code, catalogue);
        }
        if (!loaded.containsKey(DEFAULT_LANGUAGE)) throw new IllegalStateException("The English catalogue (i18n/messages_en.properties) is missing");
        this.catalogues = Map.copyOf(loaded);
        Text.install(this);
    }

    /** The English catalogue alone, for {@link Text} before the bot has made a {@code Messages}. */
    static Map<String, String> loadEnglish() {
        Map<String, String> catalogue = loadCatalogue(DEFAULT_LANGUAGE);
        if (catalogue == null) throw new IllegalStateException("The English catalogue (i18n/messages_en.properties) is missing");
        return catalogue;
    }

    /**
     * Code to its name in its own language ("en" to "English"), for the languages a server may pick: those with a catalogue, without the
     * machine-drafted packs unless {@code LANGUAGE_PACKS_ENABLED}. English first.
     */
    public Map<String, String> available() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(DEFAULT_LANGUAGE, names.getOrDefault(DEFAULT_LANGUAGE, "English"));
        catalogues.keySet().stream().filter(c -> !c.equals(DEFAULT_LANGUAGE) && isAvailable(c)).sorted().forEach(c -> out.put(c, names.get(c)));
        return out;
    }

    /** Whether a server may pick this language now. */
    public boolean isAvailable(String code) {
        return code != null && catalogues.containsKey(code) && (packsEnabled || !drafts.contains(code));
    }

    /** Whether a language is one of the machine-drafted packs (shown as such, and allowed to lack keys). */
    public boolean isDraft(String code) {
        return drafts.contains(code);
    }

    /**
     * The language a server chose, English when it chose none or one that no longer has a catalogue. A pack chosen while the packs were
     * offered keeps working after {@code LANGUAGE_PACKS_ENABLED} is switched off; it just can't be picked anew.
     */
    public String languageOf(long guildId) {
        String code = chosen.computeIfAbsent(guildId, id -> languages.find(id).orElse(DEFAULT_LANGUAGE));
        return catalogues.containsKey(code) ? code : DEFAULT_LANGUAGE;
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

    /** Every language with a catalogue, offered or not, for the consistency test. */
    Set<String> loadedLanguages() {
        return catalogues.keySet();
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

    /** The language codes listed (two letters, or like pt-BR) with their names; other lines, such as drafts=, are not languages. */
    private static Map<String, String> loadNames(Properties properties) {
        Map<String, String> out = new LinkedHashMap<>();
        if (properties != null) properties.stringPropertyNames().stream().filter(code -> code.matches("[a-z]{2}(-[A-Z]{2})?")).sorted()
                .forEach(code -> out.put(code, properties.getProperty(code)));
        out.putIfAbsent(DEFAULT_LANGUAGE, "English");
        return out;
    }

    private static Set<String> drafts(Properties properties) {
        String listed = properties == null ? null : properties.getProperty("drafts");
        if (listed == null || listed.isBlank()) return Set.of();
        return Arrays.stream(listed.split(",")).map(String::strip).filter(c -> !c.isEmpty()).collect(Collectors.toUnmodifiableSet());
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
