package com.younglings.bot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every environment setting the bot reads is listed in docs/DEV-SETUP.md (T-185), so a fresh setup can find it without reading the code.
 * The scan finds a name passed to one of the bot's ways of reading a setting, or kept in a constant named for being one.
 */
class SettingsDocumentedTest {
    private static final Pattern READ = Pattern.compile(
            "(?:getenv|envOrDotenv|positiveNumber|positiveMinutes|getLinkSetting|getRuneSyncSetting|env\\.apply|key\\(env,)\\(?\\s*\"([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\"");
    /** A renamed setting read through {@code EnvAliases}: both the new and the old name must be documented. */
    private static final Pattern ALIAS = Pattern.compile(
            "(?:envOrDotenv|EnvAliases\\.get)\\(\\s*\"([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\"\\s*,\\s*\"([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\"");
    private static final Pattern CONSTANT = Pattern.compile("(?:_VARIABLE|_ENV)\\s*=\\s*\"([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\"");

    static Set<String> settingsRead(Path sources) throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file);
                for (Pattern p : new Pattern[]{READ, CONSTANT}) {
                    Matcher m = p.matcher(source);
                    while (m.find()) names.add(m.group(1));
                }
                Matcher alias = ALIAS.matcher(source);
                while (alias.find()) {
                    names.add(alias.group(1));
                    names.add(alias.group(2));
                }
            }
        }
        return names;
    }

    @Test
    void everySettingTheBotReadsIsInTheSetupGuide() throws IOException {
        Set<String> read = settingsRead(Path.of("src/main/java"));
        assertTrue(read.size() > 30, "the scan found only " + read.size() + " settings; is it looking in the right place?");
        String guide = Files.readString(Path.of("docs/DEV-SETUP.md"));
        Set<String> missing = new TreeSet<>();
        for (String name : read) if (!Pattern.compile("\\b" + name + "\\b").matcher(guide).find()) missing.add(name);
        assertTrue(missing.isEmpty(), "add these to docs/DEV-SETUP.md section 3c (what it does, the default): " + missing);
    }
}
