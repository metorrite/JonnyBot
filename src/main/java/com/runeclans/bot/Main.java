package com.runeclans.bot;

import com.runeclans.bot.cachebus.CacheBus;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.config.BotRole;
import com.runeclans.bot.internal.GatewayForward;
import dev.freya02.botcommands.restarter.api.BotCommandsRestarter;
import io.github.freya022.botcommands.api.core.BotCommands;
import io.github.freya022.botcommands.api.core.config.BConfigBuilder;

public class Main {
    public static void main(String[] args) {
        final var config = BotConfig.getInstance();
        // T-670: the role is known before any service is built, so every service and lane can ask BotRole.current()
        BotRole.install(BotRole.checkedForStart(config.getRole(), GatewayForward.parse(config.getGatewayInternalUrl()) != null, CacheBus.on(),
                config.getInternalApiSecret() != null && config.getGuildId() != null));

        if(!config.getLiveEnvironment()) {
            BotCommandsRestarter.initialize(args, builder -> {
                // Optional configuration
            });
        }

        BotCommands.create(builder -> configure(builder, config));
    }

    /**
     * The framework configuration, shared with the headless startup check ({@code HeadlessStartup} in the tests) so the check
     * boots exactly the wiring production does.
     */
    public static void configure(BConfigBuilder builder, BotConfig config) {
        // Optionally set the owner IDs if they differ from the owners in the Discord dashboard
        // builder.addPredefinedOwners(config.getOwnerIds());

        // Add the base package of the application
        // All services and commands inside will be loaded
        builder.addSearchPath("com.runeclans.bot");
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
        // LIVE_ENV is true, no matter what GUILD_ID is set to). This also populates
        // testGuildIds, the only thing @Test-annotated commands (e.g. /dev signups) ever push
        // to — in production, that list stays empty and they register nowhere at all.
        if (!config.getLiveEnvironment() && config.getGuildId() != null) {
            builder.applicationCommands(applicationCommands -> {
                applicationCommands.forceGuildCommands(true);
                applicationCommands.getTestGuildIds().add(config.getGuildId());
            });
        }
    }
}
