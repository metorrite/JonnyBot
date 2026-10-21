package com.younglings.bot.rsnlink;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Keeps the link service movable (LINK-SYSTEM.md section 2): its classes import nothing from the rest of the bot, and the rest of the bot
 * reaches it only through {@code rsnlink.bridge}. Checked on the source, both directions.
 */
class LinkBoundaryTest {
    private static final Path MAIN = Path.of("src/main/java/com/younglings/bot");
    private static final Path SERVICE = MAIN.resolve("rsnlink");
    private static final Path BRIDGE = SERVICE.resolve("bridge");

    @Test
    void theServiceImportsNothingFromTheBot() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SERVICE)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java") && !p.startsWith(BRIDGE)).toList()) {
                for (String line : Files.readAllLines(f)) {
                    if (line.startsWith("import com.younglings.bot.") && !line.startsWith("import com.younglings.bot.rsnlink.")) offenders.add(f.getFileName() + ": " + line);
                }
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void theBotReachesTheServiceOnlyThroughTheBridge() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java") && !p.startsWith(SERVICE)).toList()) {
                for (String line : Files.readAllLines(f)) {
                    if (line.startsWith("import") && line.contains("com.younglings.bot.rsnlink") && !line.contains("com.younglings.bot.rsnlink.bridge")) offenders.add(f.getFileName() + ": " + line);
                }
            }
        }
        assertEquals(List.of(), offenders);
    }
}
