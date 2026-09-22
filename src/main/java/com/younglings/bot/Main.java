package com.younglings.bot;

import com.younglings.bot.config.BotConfig;
import dev.freya02.botcommands.restarter.api.BotCommandsRestarter;
import io.github.freya022.botcommands.api.core.BotCommands;

public class Main {
    public static void main(String[] args) {
        final var config = BotConfig.getInstance();

        if(!config.getLiveEnvironment()) {
            BotCommandsRestarter.initialize(args, builder -> {
                // Optional configuration
            });
        }

        BotCommands.create(builder -> {
            // Optionally set the owner IDs if they differ from the owners in the Discord dashboard
            // builder.addPredefinedOwners(config.getOwnerIds());

            // Add the base package of the application
            // All services and commands inside will be loaded
            builder.addSearchPath("com.younglings.bot");
            builder.components(components -> {
                components.enable(true);
            });
            builder.textCommands(textCommands -> {
                textCommands.usePingAsPrefix(true);
            });

            // Outside of production, register commands to GUILD_ID only instead of globally.
            // Guild commands sync near-instantly; global commands can take up to an hour to
            // propagate, which makes iterating on commands during development painful. Production
            // keeps registering commands globally (unaffected — this whole block is skipped when
            // LIVE_ENV is true, no matter what GUILD_ID is set to).
            if (!config.getLiveEnvironment() && config.getGuildId() != null) {
                builder.applicationCommands(applicationCommands -> {
                    applicationCommands.forceGuildCommands(true);
                    applicationCommands.getTestGuildIds().add(config.getGuildId());
                });
            }

            // When Bot is going to register its own command-cleanup shutdown hook, disable
            // BotCommands' own built-in one (BCShutdownHook) so it can't race that custom hook.
            // BotCommands' hook calls context.shutdownNow(), which independently calls
            // jda.shutdownNow() on its own — confirmed live: leaving it enabled interrupted the
            // guild-command-removal REST call mid-flight with an InterruptedIOException, even
            // after JDA's own separate shutdown hook (see Bot#createJDA) was already disabled.
            if (config.shouldManageOwnShutdown()) {
                builder.setEnableShutdownHook(false);
            }
        });
    }
}
