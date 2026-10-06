package com.younglings.bot.internal;

import com.younglings.bot.commands.poll.PollService;
import com.younglings.bot.commands.signup.SignupEntry;
import com.younglings.bot.commands.signup.SignupService;
import com.younglings.bot.commands.signup.SignupSession;
import com.younglings.bot.commands.signup.SignupType;
import com.younglings.bot.commands.signup.SubmissionField;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.runescape.PlayerLinkRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Admin controls for polls and signups, for the website. Reached only through {@link TicketAdminApi}, which has
 * already verified the acting user is an Admin or Developer. Everything here goes through the same services the
 * Discord buttons use, so the Discord messages stay in step; Discord message refreshes are debounced so a flurry
 * of admin clicks becomes one edit.
 */
@BService
public class CommunityAdminApi {
    private static final Logger log = LoggerFactory.getLogger(CommunityAdminApi.class);

    // Mirrors the limits the /poll panel in Discord enforces.
    private static final long REFRESH_DELAY_MILLIS = 800;

    private final PollService polls;
    private final SignupService signups;
    private final SiteStatsRepository stats;
    private final PlayerLinkRepository links;
    private final Debouncer debouncer;
    private final SiteCache cache;
    private final CommunitySettings community;

    public CommunityAdminApi(PollService polls, SignupService signups, SiteStatsRepository stats, PlayerLinkRepository links, Debouncer debouncer, SiteCache cache, CommunitySettings community) {
        this.community = community;
        this.polls = polls;
        this.signups = signups;
        this.stats = stats;
        this.links = links;
        this.debouncer = debouncer;
        this.cache = cache;
    }

    private static long id(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That isn't a valid id.");
        }
    }

    private static <T> T required(T value, String message) {
        if (value == null) throw new ApiError(400, message);
        return value;
    }

    // ---------- polls ----------

    DataObject createPoll(Guild guild, Member actor, DataObject body) {
        String title = body.getString("title", "").strip();
        boolean anonymous = body.getBoolean("anonymous", false);
        boolean multiple = body.getBoolean("multiple", false);

        List<String> options = new ArrayList<>();
        if (!body.isNull("options")) {
            DataArray array = body.getArray("options");
            for (int i = 0; i < array.length(); i++) {
                String option = array.getString(i).strip();
                if (!option.isEmpty()) options.add(option);
            }
        }

        Integer hours = body.isNull("durationHours") ? null : body.getInt("durationHours");
        String problem = PollRules.validate(title, options);
        if (problem == null) problem = PollRules.validateDuration(hours);
        if (problem != null) throw new ApiError(400, problem);

        GuildMessageChannel channel = postableChannel(guild, body.getString("channelId", ""), GuildMessageChannel.class);
        long pollId = polls.createPoll(guild, channel, title, anonymous, multiple, options, actor.getIdLong());
        if (hours != null) community.scheduleClose(pollId, java.time.OffsetDateTime.now().plusHours(hours));
        cache.invalidate("polls");
        log.info("Dashboard: {} created a poll '{}' in #{}", actor.getId(), title, channel.getName());
        return DataObject.empty().put("created", true);
    }

    DataObject endPoll(Guild guild, Member actor, long pollId) {
        var poll = polls.getSessionById(pollId);
        if (poll == null || poll.guildId() != guild.getIdLong()) throw new ApiError(404, "That poll doesn't exist or has already ended.");
        polls.closePoll(guild, pollId);
        cache.invalidate("polls");
        log.info("Dashboard: {} ended poll {}", actor.getId(), pollId);
        return DataObject.empty().put("ended", true);
    }

    // ---------- signups ----------

    /** The sheets as an admin sees them: the same list as the public page, but with who is who (Discord ids included). */
    DataObject signupList(Guild guild) {
        DataArray array = DataArray.empty();
        for (var sheet : stats.activeSignups(guild.getIdLong())) {
            SignupSession session = signups.getSessionById(sheet.id());
            DataArray entries = DataArray.empty();
            for (var entry : sheet.entries()) {
                entries.add(DataObject.empty().put("userId", Long.toString(entry.userId())).put("name", entryName(guild, session, entry.userId(), entry.rsn())).put("position", entry.position()));
            }
            array.add(DataObject.empty().put("id", Long.toString(sheet.id())).put("title", sheet.title()).put("type", session == null ? "QUEUE" : session.type().name())
                    .put("paused", !"ACTIVE".equalsIgnoreCase(sheet.status())).put("entries", entries));
        }
        return DataObject.empty().put("signups", array);
    }

    private String entryName(Guild guild, SignupSession session, long userId, String stored) {
        if (session == null || session.type() == SignupType.QUEUE) return stored;
        var linked = links.getLinksForUser(guild.getIdLong(), userId);
        if (!linked.isEmpty()) return linked.getFirst().rsn();
        Member member = guild.getMemberById(userId);
        return member == null ? "Member " + userId : member.getEffectiveName();
    }

    private <C extends net.dv8tion.jda.api.entities.channel.middleman.GuildChannel> C postableChannel(Guild guild, String rawId, Class<C> type) {
        long channelId = id(rawId);
        C channel = guild.getChannelById(type, channelId);
        if (channel == null) throw new ApiError(400, "That isn't a text channel in this server.");
        if (channel instanceof GuildMessageChannel messageChannel && !messageChannel.canTalk()) {
            throw new ApiError(400, "JonnyBot can't post in #" + channel.getName() + " — it needs permission to view and send messages there.");
        }
        return channel;
    }

    DataObject createSignup(Guild guild, Member actor, DataObject body) {
        SignupType type;
        try {
            type = SignupType.valueOf(body.getString("type", "QUEUE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "Choose a signup type.");
        }
        String title = body.getString("title", "").strip();
        if (title.isEmpty() || title.length() > 100) throw new ApiError(400, "The title must be 1 to 100 characters.");

        Integer max = null;
        if (!body.isNull("max")) {
            int value = body.getInt("max");
            if (value < 1 || value > 500) throw new ApiError(400, "The limit must be between 1 and 500.");
            max = value;
        }
        TextChannel signupChannel = postableChannel(guild, body.getString("signupChannelId", ""), TextChannel.class);
        TextChannel adminChannel = postableChannel(guild, body.getString("adminChannelId", ""), TextChannel.class);

        switch (type) {
            case QUEUE -> {
                String note = body.getString("note", "").strip();
                if (note.length() > 500) throw new ApiError(400, "The notification message can be at most 500 characters.");
                signups.createQueueSession(guild, signupChannel, adminChannel, title, note.isEmpty() ? null : note, max, actor.getIdLong());
            }
            case GROUP -> signups.createGroupSession(guild, signupChannel, adminChannel, title, actor.getIdLong());
            case SUBMISSION -> {
                List<SubmissionField> fields = new ArrayList<>();
                if (!body.isNull("fields")) {
                    DataArray array = body.getArray("fields");
                    for (int i = 0; i < array.length() && i < 3; i++) {
                        DataObject f = array.getObject(i);
                        String label = f.getString("label", "").strip();
                        if (label.isEmpty() || label.length() > 45) throw new ApiError(400, "Each question needs a label of 1 to 45 characters.");
                        fields.add(new SubmissionField(label, SubmissionField.normalizeType(f.getString("type", "TEXT")), f.getBoolean("required", true)));
                    }
                }
                if (fields.isEmpty()) throw new ApiError(400, "A submission signup needs at least one question.");
                signups.createSubmissionSession(guild, signupChannel, adminChannel, title, fields, max, actor.getIdLong());
            }
        }
        cache.invalidate("signups");
        log.info("Dashboard: {} created a {} signup '{}'", actor.getId(), type, title);
        return DataObject.empty().put("created", true);
    }

    private SignupSession ownSignup(Guild guild, long signupId) {
        SignupSession session = signups.getSessionById(signupId);
        if (session == null || session.guildId() != guild.getIdLong()) throw new ApiError(404, "That signup doesn't exist any more.");
        return session;
    }

    private void refresh(Guild guild, long signupId) {
        cache.invalidate("signups");
        debouncer.run("signup:" + signupId, REFRESH_DELAY_MILLIS, () -> signups.updateMessages(guild, signupId));
    }

    /** One of: pause (toggles), clear, remove (needs {@code userId}), skip, removefirst, pick, delete. */
    DataObject signupAction(Guild guild, Member actor, long signupId, String action, DataObject body) {
        ownSignup(guild, signupId);
        DataObject result = DataObject.empty().put("done", true);

        switch (action) {
            case "pause" -> {
                String status = required(signups.togglePause(signupId), "That signup doesn't exist any more.");
                result.put("paused", !"ACTIVE".equalsIgnoreCase(status));
                refresh(guild, signupId);
            }
            case "clear" -> {
                signups.clear(guild, signupId);
                refresh(guild, signupId);
            }
            case "remove" -> {
                long userId = id(body.getString("userId", ""));
                if (!signups.removeUser(guild, signupId, userId)) throw new ApiError(404, "That person isn't on the signup.");
                refresh(guild, signupId);
            }
            case "skip" -> {
                if (signups.skip(signupId) == null) throw new ApiError(409, "Nobody to skip.");
                refresh(guild, signupId);
            }
            case "removefirst" -> {
                if (signups.remove(signupId) == null) throw new ApiError(409, "Nobody to remove.");
                refresh(guild, signupId);
            }
            case "pick" -> {
                SignupEntry winner = signups.pickRandom(signupId);
                if (winner == null) throw new ApiError(409, "Nobody has signed up yet.");
                SignupSession session = signups.getSessionById(signupId);
                result.put("winner", entryName(guild, session, winner.userId(), winner.username()));
            }
            case "delete" -> {
                signups.deleteSignup(guild, signupId);
                cache.invalidate("signups");
            }
            default -> throw new ApiError(404, "Unknown action.");
        }
        log.info("Dashboard: {} {} on signup {}", actor.getId(), action, signupId);
        return result;
    }
}
