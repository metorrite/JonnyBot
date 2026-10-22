package com.younglings.bot.i18n;

import com.younglings.bot.config.BotConfig;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.Interaction;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** {@link Text}: the catalogue for code without a {@link Messages} of its own (T-098). */
class TextTest {

    @Test
    void englishTextWithPlaceholdersAndTheKeyForAnUnknownOne() {
        assertEquals("Choose between 1 and 25 people.", Text.en("split.people.range", 25));
        assertEquals("no.such.key", Text.en("no.such.key"));
    }

    @Test
    void anInteractionOutsideAServerGetsEnglish() {
        Interaction dm = mock(Interaction.class);
        assertEquals("### Loot split", Text.of(dm, "split.title"));
        assertEquals("### Loot split", Text.of((Interaction) null, "split.title"));
        assertEquals("### Loot split", Text.of((Guild) null, "split.title"));
    }

    @Test
    void aServerUsesTheBotsMessagesOnceItExists() {
        LanguageRepository languages = mock(LanguageRepository.class);
        when(languages.find(anyLong())).thenReturn(Optional.empty());
        new Messages(languages, mock(BotConfig.class)); // installs itself
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(42L);
        Interaction event = mock(Interaction.class);
        when(event.getGuild()).thenReturn(guild);
        assertEquals("**Each of 3:** 1m", Text.of(event, "split.each", 3, "1m"));
    }
}
