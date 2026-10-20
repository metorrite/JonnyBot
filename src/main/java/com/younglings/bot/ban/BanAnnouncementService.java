package com.younglings.bot.ban;

import com.younglings.bot.goodbye.GoodbyeService;
import com.younglings.bot.welcome.WelcomeConfig;
import com.younglings.bot.welcome.WelcomeMessageBuilder;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts a server's ban announcement when someone is banned. Off until a server switches it on. Nobody is ever pinged by it (the banned
 * person is no longer in the server, and the text can't mention anybody), and bots are not announced.
 * <p>
 * The ban event carries no reason, so the message can't quote one. A ban also makes the member leave, so a server with both the goodbye
 * message and this on will see both posts; switch the goodbye off if that is not wanted.
 */
@BService
public class BanAnnouncementService {
    private static final Logger log = LoggerFactory.getLogger(BanAnnouncementService.class);

    private final BanAnnouncementRepository repository;

    public BanAnnouncementService(BanAnnouncementRepository repository) {
        this.repository = repository;
    }

    /** The message for one banned person, nobody pinged. Throws {@link IllegalStateException} if the config has nothing to send. */
    public static MessageCreateData build(WelcomeConfig config, String username, String avatarUrl, String serverName, String channelName, int memberCount) {
        var lookup = GoodbyeService.lookup(username, avatarUrl, serverName, channelName, memberCount);
        return WelcomeMessageBuilder.buildSilent(config, lookup);
    }

    /** A real ban: posts this server's announcement if it is switched on. Never throws; a failure is logged. */
    public void onBan(Guild guild, User user) {
        if (user.isBot()) return;
        WelcomeConfig config = repository.get(guild.getIdLong());
        if (!config.enabled() || config.channelId() == null) return;

        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, config.channelId());
        if (channel == null || !channel.canTalk()) {
            log.warn("The ban announcement is on for guild {} but its channel is missing or JonnyBot can't post there; the ban of {} wasn't announced.", guild.getId(), user.getId());
            return;
        }
        try {
            channel.sendMessage(build(config, user.getName(), user.getEffectiveAvatarUrl(), guild.getName(), channel.getName(), guild.getMemberCount()))
                    .queue(sent -> {}, error -> log.warn("Couldn't post the ban announcement for {} in #{}", user.getId(), channel.getName(), error));
        } catch (RuntimeException e) {
            log.warn("Couldn't build the ban announcement for {}", user.getId(), e);
        }
    }

    /** What a test run did. */
    public record TestResult(String channelName) {}

    /** Posts {@code config} (an unsaved draft is fine) in its channel as if {@code subject} had just been banned. Throws {@link IllegalStateException} with a reason fit to show an admin. */
    public TestResult sendTest(Guild guild, User subject, WelcomeConfig config) {
        if (config.channelId() == null) throw new IllegalStateException("Choose a channel JonnyBot can post in.");
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, config.channelId());
        if (channel == null || !channel.canTalk()) throw new IllegalStateException("Choose a channel JonnyBot can post in.");
        MessageCreateData post;
        try {
            post = build(config, subject.getName(), subject.getEffectiveAvatarUrl(), guild.getName(), channel.getName(), guild.getMemberCount());
        } catch (RuntimeException e) {
            throw new IllegalStateException("There is nothing to send yet. Add some text or an embed.");
        }
        try {
            channel.sendMessage(post).complete();
        } catch (RuntimeException e) {
            log.warn("Ban announcement test couldn't post in #{}", channel.getName(), e);
            throw new IllegalStateException("Discord wouldn't let the test be posted in #" + channel.getName() + ".");
        }
        return new TestResult(channel.getName());
    }
}
