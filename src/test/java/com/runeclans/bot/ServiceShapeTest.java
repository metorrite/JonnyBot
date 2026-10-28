package com.runeclans.bot;

import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two mistakes that only show up when the whole bot starts (CI's {@link StartupWiringTest}, or live), caught here without a database:
 * <ul>
 *   <li>a class the framework builds ({@code @BService}, {@code @Command}, {@code @Handler}, {@code @Resolver}, {@code @BConfiguration}...)
 *   with more than one constructor: BotCommands refuses it ("must have exactly one constructor") and the bot does not start.
 *   Put a test seam in a static factory or a nested class instead;</li>
 *   <li>one table name created by two different classes: {@code CREATE TABLE IF NOT EXISTS} quietly skips the second, so its columns and
 *   indexes never exist (2026-10-21: #287's {@code ticket_flag} clashed with the tickets' own).</li>
 * </ul>
 */
class ServiceShapeTest {
    private static final Path SOURCES = Path.of("src/main/java");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    /** Schema-qualified names only (an unqualified match is prose in a comment). {@code %1$s.} is a schema filled in at runtime. */
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+(?:UNLOGGED\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?((?:%\\d\\$s|[A-Za-z_]\\w*)\\.[A-Za-z_]\\w*)", Pattern.CASE_INSENSITIVE);

    @Test
    void everyClassTheFrameworkBuildsHasExactlyOneConstructor() throws Exception {
        List<Class<?>> built = new ArrayList<>();
        for (Map.Entry<Path, String> source : sources().entrySet()) {
            Matcher pkg = PACKAGE.matcher(source.getValue());
            if (!pkg.find()) continue;
            String simple = source.getKey().getFileName().toString().replace(".java", "");
            Class<?> top;
            try {
                top = Class.forName(pkg.group(1) + "." + simple, false, getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                continue; // package-info and the like
            }
            collectBuilt(top, built);
        }
        assertTrue(built.size() > 100, "the scan found only " + built.size() + " framework classes; is it looking in the right place?");
        List<String> problems = constructorProblems(built);
        assertTrue(problems.isEmpty(), "BotCommands needs exactly one constructor (put test seams in a static factory or a nested class):\n  "
                + String.join("\n  ", problems));
    }

    @Test
    void noTableIsCreatedByTwoClasses() throws IOException {
        Map<String, Set<String>> clashes = tableClashes(sources());
        assertTrue(clashes.isEmpty(), "these tables are created in more than one file; CREATE TABLE IF NOT EXISTS skips all but the first, "
                + "so give the new one its own name:\n  " + clashes);
    }

    // ---------- the checks prove they can fail ----------

    @SuppressWarnings("unused")
    static final class TwoConstructors {
        TwoConstructors() {}
        TwoConstructors(int seam) {}
    }

    @SuppressWarnings("unused")
    static final class OneConstructor {
        private OneConstructor(String only) {}
    }

    @Test
    void aSecondConstructorIsReported() {
        List<String> problems = constructorProblems(List.of(TwoConstructors.class, OneConstructor.class));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("TwoConstructors"), problems.toString());
    }

    @Test
    void aTableCreatedInTwoFilesIsReported() {
        Map<Path, String> files = new TreeMap<>();
        files.put(Path.of("a/Tickets.java"), "\"\"\"\nCREATE TABLE IF NOT EXISTS younglings.ticket_flag (\n id BIGSERIAL\n);\n\"\"\"");
        files.put(Path.of("b/Flags.java"), "\"CREATE   TABLE if not exists Younglings.Ticket_Flag (guild_id BIGINT)\"");
        files.put(Path.of("c/Other.java"), "// see the CREATE TABLE above\n\"CREATE TABLE IF NOT EXISTS %1$s.link (id BIGINT)\"");
        files.put(Path.of("d/Link.java"), "\"CREATE TABLE IF NOT EXISTS younglings.link (id BIGINT)\"");

        Map<String, Set<String>> clashes = tableClashes(files);
        assertEquals(Set.of("younglings.ticket_flag"), clashes.keySet(), clashes.toString());
    }

    // ---------- helpers ----------

    private static Map<Path, String> sources() throws IOException {
        Map<Path, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                out.put(SOURCES.relativize(file), Files.readString(file));
            }
        }
        return out;
    }

    /** The class and its nested classes that the framework would build: concrete, and marked as a service or a configuration. */
    private static void collectBuilt(Class<?> type, List<Class<?>> out) {
        if (!type.isInterface() && !Modifier.isAbstract(type.getModifiers()) && !type.isEnum() && isBuilt(type)) out.add(type);
        for (Class<?> nested : type.getDeclaredClasses()) collectBuilt(nested, out);
    }

    /** {@code @BService}, {@code @BConfiguration}, or an annotation that is itself a service annotation ({@code @Command}, {@code @Handler}, {@code @Resolver}...). */
    private static boolean isBuilt(Class<?> type) {
        for (Annotation a : type.getDeclaredAnnotations()) {
            Class<? extends Annotation> kind = a.annotationType();
            if (kind == BService.class || kind == BConfiguration.class || kind.isAnnotationPresent(BService.class)) return true;
        }
        return false;
    }

    static List<String> constructorProblems(List<Class<?>> classes) {
        List<String> problems = new ArrayList<>();
        for (Class<?> type : classes) {
            Constructor<?>[] constructors = Arrays.stream(type.getDeclaredConstructors()).filter(c -> !c.isSynthetic()).toArray(Constructor[]::new);
            if (constructors.length != 1) problems.add(type.getName() + " has " + constructors.length + " constructors");
        }
        return problems;
    }

    /** Table name (lower case) -> the files that create it, for names created in more than one file. */
    static Map<String, Set<String>> tableClashes(Map<Path, String> files) {
        Map<String, Set<String>> byTable = new TreeMap<>();
        for (Map.Entry<Path, String> file : files.entrySet()) {
            Matcher m = CREATE_TABLE.matcher(file.getValue());
            while (m.find()) byTable.computeIfAbsent(m.group(1).toLowerCase(Locale.ROOT), k -> new TreeSet<>()).add(file.getKey().toString().replace('\\', '/'));
        }
        byTable.values().removeIf(where -> where.size() < 2);
        return byTable;
    }
}
