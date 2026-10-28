package com.runeclans.bot;

import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code @BService} that extends {@code ListenerAdapter} is never handed to JDA by the framework: it must be a field of {@link Bot}
 * (named in {@code createJDA}) or implement {@link BotListener}. A forgotten one makes every button, select and modal it owns time out
 * with no error at all, so this fails the build instead.
 */
class ListenerRegistrationTest {
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern CLASS = Pattern.compile("^(?:public\\s+)?(?:final\\s+)?class\\s+(\\w+)[^{]*\\bextends\\s+ListenerAdapter\\b", Pattern.MULTILINE);

    @Test
    void everyListenerServiceIsRegisteredWithJda() throws Exception {
        Set<Class<?>> registered = new HashSet<>();
        for (var field : Bot.class.getDeclaredFields()) registered.add(field.getType());

        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String source = read(file);
                if (!source.contains("@BService")) continue;
                Matcher pkg = PACKAGE.matcher(source);
                Matcher cls = CLASS.matcher(source);
                if (!pkg.find() || !cls.find()) continue;
                Class<?> type = Class.forName(pkg.group(1) + "." + cls.group(1));
                if (!ListenerAdapter.class.isAssignableFrom(type)) continue;
                if (!registered.contains(type) && !BotListener.class.isAssignableFrom(type)) missing.add(type.getName());
            }
        }
        assertTrue(missing.isEmpty(), "ListenerAdapter services not registered with JDA (add them to Bot.createJDA, or implement BotListener): " + missing);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
