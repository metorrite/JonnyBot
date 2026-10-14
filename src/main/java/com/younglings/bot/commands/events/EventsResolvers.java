package com.younglings.bot.commands.events;

import com.younglings.bot.events.EventQuickCreate;
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
}
