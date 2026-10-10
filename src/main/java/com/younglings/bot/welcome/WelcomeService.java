package com.younglings.bot.welcome;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends the welcome message. The welcome channel always gets it; if the server also asked for a DM, the member is sent
 * a copy too when their DMs are open (and, when they aren't, that is the end of it: the channel post already happened).
 * Bots are never welcomed, as with Dyno.
 */
@BService
public class WelcomeService {
    private static final Logger log = LoggerFactory.getLogger(WelcomeService.class);

    private final WelcomeRepository repository;

    public WelcomeService(WelcomeRepository repository) {
        this.repository = repository;
    }

    /** A real join: sends this server's welcome if it is switched on. Never throws; a failure is logged. */
    public void welcomeNewMember(Member member) {
        if (member.getUser().isBot()) return;
        Guild guild = member.getGuild();
        WelcomeConfig config = repository.get(guild.getIdLong());
        if (!config.enabled()) return;

        GuildMessageChannel channel = postableChannel(guild, config);
        if (channel == null) {
            log.warn("The welcome is on for guild {} but its channel is missing or JonnyBot can't post there; {} wasn't welcomed.", guild.getIdLong(), member.getId());
        } else {
            try {
                channel.sendMessage(WelcomeMessageBuilder.build(config, new GuildLookup(guild, member, channel.getName()), member.getIdLong(), true))
                        .queue(sent -> {}, error -> log.warn("Couldn't post the welcome for {} in #{}", member.getId(), channel.getName(), error));
            } catch (RuntimeException e) {
                log.warn("Couldn't build the welcome for {}", member.getId(), e);
            }
        }

        if (config.alsoDm()) {
            String channelName = channel == null ? "" : channel.getName();
            try {
                MessageCreateData dm = WelcomeMessageBuilder.build(config, new GuildLookup(guild, member, channelName), member.getIdLong(), false);
                member.getUser().openPrivateChannel()
                        .flatMap(privateChannel -> privateChannel.sendMessage(dm))
                        .queue(sent -> {}, error -> log.info("Couldn't DM the welcome to {} (their DMs are probably closed)", member.getId()));
            } catch (RuntimeException e) {
                log.warn("Couldn't build the welcome DM for {}", member.getId(), e);
            }
        }
    }

    /** What a test run did. */
    public record TestResult(String channelName, boolean dmSent) {}

    /**
     * Sends {@code config} (which may be an unsaved draft) as if {@code actor} had just joined: a real post in the
     * welcome channel, and, when the draft asks for a DM, a copy to the actor. Returns what happened, or throws
     * {@link IllegalStateException} with a reason fit to show an admin.
     */
    public TestResult sendTest(Guild guild, Member actor, WelcomeConfig config) {
        GuildMessageChannel channel = postableChannel(guild, config);
        if (channel == null) throw new IllegalStateException("Choose a channel JonnyBot can post in.");

        MessageCreateData post;
        try {
            post = WelcomeMessageBuilder.build(config, new GuildLookup(guild, actor, channel.getName()), actor.getIdLong(), true);
        } catch (RuntimeException e) {
            throw new IllegalStateException("There is nothing to send yet. Add some text or an embed.");
        }
        try {
            channel.sendMessage(post).complete();
        } catch (RuntimeException e) {
            log.warn("Welcome test couldn't post in #{}", channel.getName(), e);
            throw new IllegalStateException("Discord wouldn't let the test be posted in #" + channel.getName() + ".");
        }

        boolean dmSent = false;
        if (config.alsoDm()) {
            try {
                MessageCreateData dm = WelcomeMessageBuilder.build(config, new GuildLookup(guild, actor, channel.getName()), actor.getIdLong(), false);
                actor.getUser().openPrivateChannel().flatMap(privateChannel -> privateChannel.sendMessage(dm)).complete();
                dmSent = true;
            } catch (RuntimeException e) {
                log.info("Welcome test couldn't DM {} (their DMs are probably closed)", actor.getId());
            }
        }
        return new TestResult(channel.getName(), dmSent);
    }

    /** The configured welcome channel, if it still exists and JonnyBot can talk in it. */
    GuildMessageChannel postableChannel(Guild guild, WelcomeConfig config) {
        if (config.channelId() == null) return null;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, config.channelId());
        return channel != null && channel.canTalk() ? channel : null;
    }

    /** Answers a message's questions about one member joining one server. */
    static final class GuildLookup implements WelcomeTemplate.Lookup {
        private final Guild guild;
        private final Member member;
        private final String channelName;

        GuildLookup(Guild guild, Member member, String channelName) {
            this.guild = guild;
            this.member = member;
            this.channelName = channelName;
        }

        @Override
        public String userMention() {
            return member.getAsMention();
        }

        @Override
        public String username() {
            return member.getUser().getName();
        }

        @Override
        public String avatarUrl() {
            return member.getEffectiveAvatarUrl();
        }

        @Override
        public String serverName() {
            return guild.getName();
        }

        @Override
        public String channelName() {
            return channelName;
        }

        @Override
        public int memberCount() {
            return guild.getMemberCount();
        }

        /** Best effort: the bot only keeps online members in memory, so someone offline can't be found by name. */
        @Override
        public String findUser(String name) {
            return guild.getMembersByEffectiveName(name, true).stream().findFirst()
                    .or(() -> guild.getMembersByName(name, true).stream().findFirst())
                    .map(Member::getAsMention).orElse(null);
        }

        @Override
        public String findRole(String name) {
            return guild.getRolesByName(name, true).stream().findFirst().map(role -> role.getAsMention()).orElse(null);
        }

        @Override
        public String findChannel(String name) {
            return guild.getChannels().stream().filter(channel -> channel.getName().equalsIgnoreCase(name)).findFirst()
                    .map(GuildChannel::getAsMention).orElse(null);
        }
    }
}
