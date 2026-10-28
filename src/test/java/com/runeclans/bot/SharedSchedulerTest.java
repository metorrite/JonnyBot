package com.runeclans.bot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Background timers use the shared {@code BotScheduler} (T-184): a service asks for {@code BotScheduler.lane("name")} instead of starting a
 * thread of its own that sits idle almost all the time. Pools that really run work side by side are listed here on purpose.
 */
class SharedSchedulerTest {
    /** Real parallel work: several event spaces or wrap-ups at once. */
    private static final Set<String> PARALLEL_POOLS = Set.of("EventSpaceOps.java", "EventWrapupOps.java", "BotScheduler.java");

    @Test
    void noServiceStartsAThreadOfItsOwnForATimer() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (PARALLEL_POOLS.contains(file.getFileName().toString())) continue;
                String source = Files.readString(file);
                if (source.contains("newSingleThreadScheduledExecutor") || source.contains("newScheduledThreadPool")) found.add(file.getFileName().toString());
            }
        }
        assertTrue(found.isEmpty(), "use BotScheduler.lane(\"name\") instead of a scheduler of its own: " + found);
    }
}
