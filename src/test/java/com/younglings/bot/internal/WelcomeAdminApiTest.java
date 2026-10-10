package com.younglings.bot.internal;

import com.younglings.bot.welcome.WelcomeConfig;
import com.younglings.bot.welcome.WelcomeConfig.MessageType;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reading the website's request into a welcome message. */
class WelcomeAdminApiTest {
    @Test
    void aFullRequestIsReadFieldByField() {
        var body = DataObject.fromJson("""
                {"enabled":true,"messageType":"EMBED","channelId":"1234567890123456789","alsoDm":true,
                 "content":"Hi {user}   ","color":11146786,"title":" Welcome ","titleUrl":"https://a.example","description":"Body\\n",
                 "authorName":"The Younglings","authorIconUrl":"https://i.example/a.png","thumbnailUrl":"","imageUrl":"https://i.example/b.png",
                 "footerText":"Clan","footerIconUrl":"","linkButton":true,"linkButtonLabel":" Verify ",
                 "fields":[{"name":" N ","value":"V","inline":true},{"name":"N2","value":"V2"}]}
                """);

        WelcomeConfig c = WelcomeAdminApi.parse(5L, body);

        assertEquals(5L, c.guildId());
        assertTrue(c.enabled());
        assertEquals(MessageType.EMBED, c.messageType());
        assertEquals(1234567890123456789L, c.channelId());
        assertTrue(c.alsoDm());
        assertEquals("Hi {user}", c.content(), "trailing spaces are dropped");
        assertEquals(11146786, c.color());
        assertEquals("Welcome", c.title());
        assertEquals("Body", c.description());
        assertEquals("Verify", c.linkButtonLabel());
        assertEquals(2, c.fields().size());
        assertEquals("N", c.fields().getFirst().name());
        assertTrue(c.fields().getFirst().inline());
        assertFalse(c.fields().get(1).inline());
    }

    @Test
    void missingFieldsFallBackToSafeDefaults() {
        WelcomeConfig c = WelcomeAdminApi.parse(5L, DataObject.fromJson("{}"));
        assertFalse(c.enabled());
        assertEquals(MessageType.EMBED_TEXT, c.messageType());
        assertNull(c.channelId());
        assertNull(c.color());
        assertTrue(c.fields().isEmpty());
        assertFalse(c.linkButton());
    }

    @Test
    void aNullChannelOrColourMeansNone() {
        WelcomeConfig c = WelcomeAdminApi.parse(5L, DataObject.fromJson("{\"channelId\":null,\"color\":null}"));
        assertNull(c.channelId());
        assertNull(c.color());
    }

    @Test
    void nonsenseIsRefusedWithAReasonNotAStackTrace() {
        assertEquals("Choose a message type.", assertThrows(TicketAdminApi.ApiError.class, () -> WelcomeAdminApi.parse(1L, DataObject.fromJson("{\"messageType\":\"POSTCARD\"}"))).getMessage());
        assertEquals("That isn't a valid channel.", assertThrows(TicketAdminApi.ApiError.class, () -> WelcomeAdminApi.parse(1L, DataObject.fromJson("{\"channelId\":\"abc\"}"))).getMessage());
        assertEquals("The embed colour isn't a valid colour.", assertThrows(TicketAdminApi.ApiError.class, () -> WelcomeAdminApi.parse(1L, DataObject.fromJson("{\"color\":99999999}"))).getMessage());
    }

    @Test
    void aHugeListOfFieldsIsCappedWhenReadSoTheValidatorCanSayTooMany() {
        var sb = new StringBuilder("{\"fields\":[");
        for (int i = 0; i < 100; i++) sb.append(i == 0 ? "" : ",").append("{\"name\":\"n\",\"value\":\"v\"}");
        sb.append("]}");
        assertEquals(40, WelcomeAdminApi.parse(1L, DataObject.fromJson(sb.toString())).fields().size());
    }
}
