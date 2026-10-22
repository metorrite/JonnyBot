package com.younglings.bot.internal;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.i18n.LanguageRepository;
import com.younglings.bot.i18n.Messages;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LanguageAdminApiTest {
    @Test
    void theLanguagePageRouteReadsAndSaves() {
        LanguageRepository repository = mock(LanguageRepository.class);
        when(repository.find(anyLong())).thenReturn(Optional.empty());
        LanguageAdminApi api = new LanguageAdminApi(new Messages(repository, mock(BotConfig.class)));
        Guild guild = mock(Guild.class);
        Member actor = mock(Member.class);
        when(guild.getIdLong()).thenReturn(9L);
        when(guild.getId()).thenReturn("9");
        when(actor.getId()).thenReturn("1");

        DataObject answer = api.route(guild, actor, "GET", new String[]{"language"}, DataObject::empty);
        assertEquals("en", answer.getString("language"));
        assertEquals("English", answer.getArray("languages").getObject(0).getString("name"));

        api.route(guild, actor, "PUT", new String[]{"language"}, () -> DataObject.fromJson("{\"language\":\"en\"}"));
        verify(repository).save(9L, "en");
        var thrown = assertThrows(TicketAdminApi.ApiError.class,
                () -> api.route(guild, actor, "PUT", new String[]{"language"}, () -> DataObject.fromJson("{\"language\":\"zz\"}")));
        assertTrue(thrown.getMessage().contains("isn't available"));
        assertNull(api.route(guild, actor, "GET", new String[]{"other"}, DataObject::empty));
    }
}
