package com.runeclans.bot.leagues;

import com.runeclans.bot.alerts.AutoPostRouter;
import com.runeclans.bot.config.BotConfig;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.tracking.TrackingPostingToggle;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The league post job (T-760): skipped while the bot is not live, no pings and no link embeds when it is, Discord's length respected, a
 * missing or unusable channel refused, and never retried by itself after an unclear failure.
 */
class LeaguePostJobsTest {
    private static final long GUILD = Long.parseLong("2".repeat(18));
    private static final long CHANNEL = Long.parseLong("3".repeat(18));

    private final JDA jda = mock(JDA.class);
    private final Guild guild = mock(Guild.class);
    private final GuildMessageChannel channel = mock(GuildMessageChannel.class);
    private final MessageCreateAction action = mock(MessageCreateAction.class);
    private final LeaguePostJobs jobs = new LeaguePostJobs();

    @AfterEach
    void reset() {
        AutoPostRouter.reset();
    }

    /** Builds the router the way the bot does, with posting switched on (live) or off (not live). */
    private static void router(boolean live) {
        TrackingPostingToggle posting = mock(TrackingPostingToggle.class);
        when(posting.isEnabled()).thenReturn(live);
        new AutoPostRouter(mock(BotConfig.class), posting);
    }

    private void server() throws Exception {
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getChannelById(GuildMessageChannel.class, Long.toString(CHANNEL))).thenReturn(channel);
        when(channel.canTalk()).thenReturn(true);
        when(channel.getName()).thenReturn("league-news");
        when(channel.sendMessage(anyString())).thenReturn(action);
        when(action.setAllowedMentions(any())).thenReturn(action);
        when(action.setSuppressEmbeds(true)).thenReturn(action);
        Message sent = mock(Message.class);
        when(sent.getId()).thenReturn("40");
        when(action.complete(false)).thenReturn(sent);
    }

    private static DataObject payload(String text) {
        return DataObject.empty().put("channelId", Long.toString(CHANNEL)).put("leagueId", "0dc0dc00-0000-4000-8000-00000000000b").put("text", text);
    }

    private DiscordJob job(DataObject payload) {
        return new DiscordJob(5L, LeaguePostJobs.KIND, GUILD, 0L, null, payload, DiscordJob.State.RUNNING, 1, 0, null, null, null, null, null);
    }

    @Test
    void aBotThatIsNotLivePostsNothing() throws Exception {
        router(false);
        server();
        DataObject result = jobs.run(jda, job(payload("Round 2 of **Winter cup** has started.")));
        assertEquals("dev", result.getString("skipped"));
        verify(channel, never()).sendMessage(anyString());
    }

    @Test
    void theLiveBotPostsWithoutPingsOrLinkEmbedsAndIsNotRetriedByItself() throws Exception {
        router(true);
        server();
        DataObject result = jobs.run(jda, job(payload("A league was proposed: **\\[Claim your prize\\]\\(https://evil.example\\)** @everyone <@&1>")));
        assertEquals("40", result.getString("messageId"));
        ArgumentCaptor<EnumSet<Message.MentionType>> mentions = ArgumentCaptor.forClass(EnumSet.class);
        verify(action).setAllowedMentions(mentions.capture());
        assertEquals(EnumSet.noneOf(Message.MentionType.class), mentions.getValue(), "nobody is pinged, whatever the text says");
        verify(action).setSuppressEmbeds(true);
        verify(action, never()).mentionRoles(any(long[].class));
        assertEquals(LeaguePostJobs.KIND, jobs.kind());
        assertFalse(jobs.idempotent(), "a post that may have gone out is not sent again after an unclear failure");
    }

    @Test
    void aLongPostIsCutToDiscordsLength() throws Exception {
        router(true);
        server();
        jobs.post(guild, payload("x".repeat(LeaguePostJobs.MAX_TEXT + 600)));
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(channel).sendMessage(text.capture());
        assertEquals(LeaguePostJobs.MAX_TEXT, text.getValue().length());
    }

    @Test
    void nothingToPostAMissingChannelAndOneTheBotCantTalkInAreRefused() throws Exception {
        router(true);
        server();
        assertThrows(DiscordJobs.Refused.class, () -> jobs.post(guild, payload("  ")), "no text");
        assertThrows(DiscordJobs.Refused.class, () -> jobs.post(guild, payload("Hello").put("channelId", "abc")), "no channel id");
        when(channel.canTalk()).thenReturn(false);
        assertThrows(DiscordJobs.Refused.class, () -> jobs.post(guild, payload("Hello")), "the bot can't post there");
        when(guild.getChannelById(GuildMessageChannel.class, Long.toString(CHANNEL))).thenReturn(null);
        assertThrows(DiscordJobs.Refused.class, () -> jobs.post(guild, payload("Hello")), "the channel is gone");
        verify(channel, never()).sendMessage(anyString());
    }
}
