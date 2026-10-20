package com.younglings.bot.testsupport;

import com.younglings.bot.Bot;
import com.younglings.bot.Main;
import com.younglings.bot.config.BotConfig;
import io.github.freya022.botcommands.api.core.BContext;
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandManager;
import io.github.freya022.botcommands.api.commands.application.provider.GlobalApplicationCommandProvider;
import io.github.freya022.botcommands.api.commands.application.provider.GuildApplicationCommandManager;
import io.github.freya022.botcommands.api.commands.application.provider.GuildApplicationCommandProvider;
import io.github.freya022.botcommands.api.core.BotCommands;
import net.dv8tion.jda.api.entities.Guild;
import org.mockito.Mockito;

/**
 * Child-process entry point of {@code StartupWiringTest}: builds the same BotCommands context as {@link Main} (same search path, same
 * configuration) with {@link Bot#HEADLESS_PROPERTY} set, so every service is constructed and every command definition is built, but
 * Discord is never contacted. Exit code 0 means the wiring is sound; anything else prints the framework's own error.
 */
public final class HeadlessStartup {
    private HeadlessStartup() {}

    /**
     * The framework builds the slash-command definitions only once a JDA exists (and then pushes them to Discord). The declaration step
     * itself needs no JDA, and it is where a missing parameter resolver, a bad option or a duplicate command name throws, so it is run
     * here the way {@code ApplicationCommandsBuilder} does, without the push. Guild-scoped providers get a made-up guild.
     */
    private static void declareCommands(BContext context) {
        var container = context.getServiceContainer();
        var globalManager = new GlobalApplicationCommandManager(context);
        for (var provider : container.getInterfacedServices(GlobalApplicationCommandProvider.class)) {
            provider.declareGlobalApplicationCommands(globalManager);
        }
        Guild guild = Mockito.mock(Guild.class);
        Mockito.when(guild.getIdLong()).thenReturn(1L);
        Mockito.when(guild.getId()).thenReturn("1");
        Mockito.when(guild.getName()).thenReturn("headless check");
        var guildManager = new GuildApplicationCommandManager(context, guild);
        for (var provider : container.getInterfacedServices(GuildApplicationCommandProvider.class)) {
            provider.declareGuildApplicationCommands(guildManager);
        }
    }

    public static void main(String[] args) {
        int code = 1;
        try {
            BContext context = BotCommands.create(builder -> Main.configure(builder, BotConfig.getInstance()));
            declareCommands(context);
            System.out.println("HEADLESS_STARTUP_OK status=" + context.getStatus());
            code = 0;
        } catch (Throwable t) {
            System.out.println("HEADLESS_STARTUP_FAILED");
            t.printStackTrace(System.out);
        }
        // The framework leaves non-daemon threads (connection pool, schedulers) running; the check is over either way.
        Runtime.getRuntime().halt(code);
    }
}
