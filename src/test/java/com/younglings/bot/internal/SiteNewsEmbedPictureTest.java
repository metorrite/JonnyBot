package com.younglings.bot.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.dv8tion.jda.api.entities.MessageEmbed;
import org.junit.jupiter.api.Test;

/** The website's news feed draws embed pictures only from Discord's own hosts, so the feed sends Discord's proxied copies. */
class SiteNewsEmbedPictureTest {
    private static final String ORIGINAL = "https://example.com/picture.png";
    private static final String PROXIED = "https://media.discordapp.net/external/abc/https/example.com/picture.png";

    @Test
    void embedImageIsSentAsDiscordsProxiedCopy() {
        assertEquals(PROXIED, SiteNewsService.embedImageUrl(new MessageEmbed.ImageInfo(ORIGINAL, PROXIED, 100, 100)));
    }

    @Test
    void embedThumbnailIsSentAsDiscordsProxiedCopy() {
        assertEquals(PROXIED, SiteNewsService.embedThumbnailUrl(new MessageEmbed.Thumbnail(ORIGINAL, PROXIED, 100, 100)));
    }

    @Test
    void missingPicturesStayEmpty() {
        assertNull(SiteNewsService.embedImageUrl(null));
        assertNull(SiteNewsService.embedThumbnailUrl(null));
        assertNull(SiteNewsService.embedImageUrl(new MessageEmbed.ImageInfo(ORIGINAL, null, 100, 100)));
    }
}
