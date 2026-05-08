package com.younglings.bot;

import com.younglings.bot.config.BotConfig;
import dev.freya02.botcommands.restarter.api.BotCommandsRestarter;
import io.github.freya022.botcommands.api.core.BotCommands;
import io.github.freya022.botcommands.api.core.config.BApplicationConfigBuilder;

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
        });
    }
}
