package com.runeclans.bot.commands.events;

import com.runeclans.bot.events.BingoLooks;
import com.runeclans.bot.events.EventQuickCreate;
import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.Resolver;
import io.github.freya022.botcommands.api.parameters.ParameterResolver;
import io.github.freya022.botcommands.api.parameters.Resolvers;

@BConfiguration
public class EventsResolvers {

    /** Backs {@code @SlashOption(usePredefinedChoices = true) EventQuickCreate.Kind} with a dropdown of Bingo, XP competition, ... */
    @Resolver
    public static ParameterResolver<?, ?> eventKindResolver() {
        return Resolvers.enumResolver(EventQuickCreate.Kind.class)
                .setNameFunction(kind -> kind.label)
                .build();
    }

    /** Backs {@code @SlashOption(usePredefinedChoices = true) EventQuickCreate.Look} with a dropdown of RuneScape and Template. */
    @Resolver
    public static ParameterResolver<?, ?> bingoLookResolver() {
        return Resolvers.enumResolver(EventQuickCreate.Look.class)
                .setNameFunction(look -> BingoLooks.label(look.id))
                .build();
    }
}
