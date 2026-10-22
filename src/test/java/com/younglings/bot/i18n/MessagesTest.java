package com.younglings.bot.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessagesTest {
    private final LanguageRepository repository = mock(LanguageRepository.class);

    private Messages messages() {
        when(repository.find(anyLong())).thenReturn(Optional.empty());
        return new Messages(repository);
    }

    @Test
    void englishIsTheDefaultAndPlaceholdersAreFilledInOrder() {
        Messages m = messages();
        assertEquals("en", m.languageOf(1L));
        assertEquals("Choose between 1 and 25 people.", m.text(1L, "split.people.range", 25));
        assertEquals("**Each of 3:** 1.5m", m.text(1L, "split.each", 3, "1.5m"));
        assertEquals("**Fee (5%):** 10k", m.text("en", "split.fee", "5", "10k"));
    }

    @Test
    void anUnknownKeyShowsTheKeyInsteadOfFailing() {
        assertEquals("no.such.key", messages().text(1L, "no.such.key"));
    }

    @Test
    void aServerThatChoseALanguageNoLongerAvailableFallsBackToEnglish() {
        when(repository.find(7L)).thenReturn(Optional.of("xx"));
        Messages m = new Messages(repository);
        assertEquals("en", m.languageOf(7L));
        assertEquals("The fee is a percentage from 0 to 100.", m.text(7L, "split.fee.range"));
    }

    @Test
    void aMissingKeyInAnotherLanguageFallsBackToEnglishText() {
        Messages m = messages();
        // "xx" has no catalogue, which is the same as a pack that lacks the key
        assertEquals("The fee is a percentage from 0 to 100.", m.text("xx", "split.fee.range"));
    }

    @Test
    void settingALanguageSavesItAndRefusesUnknownOnes() {
        Messages m = messages();
        m.setLanguage(5L, "en");
        verify(repository).save(5L, "en");
        assertThrows(IllegalArgumentException.class, () -> m.setLanguage(5L, "klingon"));
        verify(repository, never()).save(eq(5L), eq("klingon"));
    }

    /**
     * Every key line in the English file is really loaded. A value ending in a lone backslash (say a trailing "\ " whose space an editor
     * stripped) joins the next line onto it, silently swallowing that key; write a trailing space as   instead.
     */
    @Test
    void noKeyIsSwallowedByTheLineBeforeIt() throws IOException {
        List<String> lines = Files.readAllLines(Path.of("src/main/resources/i18n/messages_en.properties"));
        long written = lines.stream().filter(l -> l.matches("[a-z0-9_.]+=.*")).count();
        assertEquals(written, messages().catalogue("en").size(), "a line ending in a backslash swallowed the key after it");
    }

    /** Every language pack must have exactly English's keys and placeholders, and every key the code asks for must exist in English. */
    @Test
    void theCataloguesAreConsistentAndEveryKeyUsedInCodeExists() throws IOException {
        Messages m = messages();
        var english = m.catalogue("en");
        Pattern placeholder = Pattern.compile("\\{\\d+}");
        List<String> problems = new ArrayList<>();
        for (String code : m.available().keySet()) {
            var catalogue = m.catalogue(code);
            for (var entry : english.entrySet()) {
                String translated = catalogue.get(entry.getKey());
                if (translated == null) {
                    problems.add(code + " lacks " + entry.getKey());
                    continue;
                }
                if (!placeholders(placeholder, entry.getValue()).equals(placeholders(placeholder, translated))) problems.add(code + " has different placeholders in " + entry.getKey());
            }
            catalogue.keySet().stream().filter(k -> !english.containsKey(k)).forEach(k -> problems.add(code + " has a key English lacks: " + k));
        }

        // keys written as string literals in messages.text(...) and Text.of(...) / Text.en(...) calls (T-098), every one in the call, so a
        // key picked by a ternary (Text.of(event, ok ? "a.done" : "a.failed")) is checked on both sides
        Pattern call = Pattern.compile("(?:(?:messages|msg)\\.text|Text\\.(?:of|en))\\(");
        Pattern key = Pattern.compile("\"([a-z0-9_]+(?:\\.[a-z0-9_]+)+)\"");
        int seen = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = Files.readString(file);
                Matcher found = call.matcher(source);
                while (found.find()) {
                    Matcher keys = key.matcher(arguments(source, found.end()));
                    while (keys.find()) {
                        seen++;
                        if (!english.containsKey(keys.group(1))) problems.add(file.getFileName() + " asks for a message key English lacks: " + keys.group(1));
                    }
                }
            }
        }
        assertTrue(seen > 0, "the scan found no message keys in the code; is it looking in the right place?");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /** The source of a call's argument list, from just after its "(" to the matching ")" (strings skipped, so a ")" in text doesn't end it). */
    private static String arguments(String source, int from) {
        int depth = 1;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                for (i++; i < source.length() && source.charAt(i) != '"'; i++) if (source.charAt(i) == '\\') i++;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return source.substring(from, i);
            }
        }
        return source.substring(from);
    }

    private static List<String> placeholders(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        List<String> out = new ArrayList<>();
        while (m.find()) out.add(m.group());
        out.sort(String::compareTo);
        return out;
    }
}
