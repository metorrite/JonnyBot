package com.runeclans.bot.fun;

import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.Resolver;
import io.github.freya022.botcommands.api.parameters.ParameterResolver;
import io.github.freya022.botcommands.api.parameters.Resolvers;

@BConfiguration
public class FunResolvers {

    /** Backs {@code @SlashOption(usePredefinedChoices = true) FunLogic.Move} with a dropdown of Rock, Paper and Scissors. */
    @Resolver
    public static ParameterResolver<?, ?> rpsMoveResolver() {
        return Resolvers.enumResolver(FunLogic.Move.class)
                .setNameFunction(move -> move.label)
                .build();
    }
}
