package com.younglings.bot.commands.signup;

import io.github.freya022.botcommands.api.core.service.annotations.BConfiguration;
import io.github.freya022.botcommands.api.core.service.annotations.Resolver;
import io.github.freya022.botcommands.api.parameters.ParameterResolver;
import io.github.freya022.botcommands.api.parameters.Resolvers;

@BConfiguration
public class SignupResolvers {

    /** Backs {@code @SlashOption(usePredefinedChoices = true) SignupPanelType} with a real PUBLIC/ADMIN dropdown. */
    @Resolver
    public static ParameterResolver<?, ?> signupPanelTypeResolver() {
        return Resolvers.enumResolver(SignupPanelType.class).build();
    }
}
