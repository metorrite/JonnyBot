package com.younglings.bot.commands.recap;

import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.Resolver;
import io.github.freya022.botcommands.api.parameters.ParameterResolver;
import io.github.freya022.botcommands.api.parameters.Resolvers;

@BConfiguration
public class RecapResolvers {

    /** Backs {@code @SlashOption(usePredefinedChoices = true) Period} with a dropdown of "This week", "Last month", ... */
    @Resolver
    public static ParameterResolver<?, ?> recapPeriodResolver() {
        return Resolvers.enumResolver(WrappedCommand.Period.class)
                .setNameFunction(period -> period.label)
                .build();
    }
}
