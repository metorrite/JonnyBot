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

            // Note: the guild-command-cleanup shutdown hook (see Bot#registerCommandCleanupShutdownHook)
            // deliberately does NOT touch this builder's/JDA's own shutdown hook settings. An
            // earlier attempt disabled both JDA's and BotCommands' built-in shutdown hooks to
            // avoid racing a custom one for the same REST call — each attempt uncovered a new
            // failure mode from fighting the framework's shutdown machinery (a race against JDA's
            // hook, then a race against BotCommands' own, then a crash from a @Lazy service
            // resolution edge case when that second hook is disabled). Sending the cleanup request
            // via a plain HTTPS call instead of through JDA sidesteps needing to touch any of this.
        });
    }
}
