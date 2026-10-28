package com.runeclans.bot.internal;

import com.runeclans.bot.ban.AnnounceService;
import com.runeclans.bot.ban.BanAnnouncementRepository;
import com.runeclans.bot.ban.BanAnnouncementService;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordOutbox;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
import com.runeclans.bot.welcome.WelcomeConfig;
import com.runeclans.bot.welcome.WelcomeValidator;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The website's side of the announcements: the ban announcement (read, save, send a test; the same message shape as the welcome, so the
 * shared message editor edits it) and "send an announcement" (the dashboard twin of {@code /announce}). Reached only through
 * {@link TicketAdminApi}, which has already checked the asker may use the dashboard in this server and audit-logs every write.
 */
@BService
public class AnnouncementsAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(AnnouncementsAdminApi.class);

    private final BanAnnouncementRepository repository;
    private final BanAnnouncementService banService;
    private final AnnounceService announceService;
    private final DiscordOutbox outbox;

    public AnnouncementsAdminApi(BanAnnouncementRepository repository, BanAnnouncementService banService, AnnounceService announceService, DiscordOutbox outbox) {
        this.repository = repository;
        this.banService = banService;
        this.announceService = announceService;
        this.outbox = outbox;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length == 1 && parts[0].equals("ban-announcement")) {
            if (method.equals("GET")) return get(guild);
            AdminRouteExtension.requireMethod(method, "PUT");
            return save(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[0].equals("ban-announcement") && parts[1].equals("test")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return test(guild, actor, body.get());
        }
        if (parts.length == 2 && parts[0].equals("announce") && parts[1].equals("send")) {
            AdminRouteExtension.requireMethod(method, "POST");
            return send(guild, actor, body.get());
        }
        return null;
    }

    DataObject get(Guild guild) {
        return WelcomeAdminApi.toJson(guild, repository.get(guild.getIdLong()));
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        WelcomeConfig config = parseAndCheck(guild, body);
        repository.save(config);
        log.info("Dashboard: {} saved the ban announcement (enabled={}, type={})", actor.getId(), config.enabled(), config.messageType());
        return get(guild);
    }

    /** Posts the draft in its channel as if the acting admin had just been banned. Nobody is pinged. */
    DataObject test(Guild guild, Member actor, DataObject body) {
        WelcomeConfig config = parseAndCheck(guild, body);
        if (config.channelId() == null) throw new ApiError(400, Text.of(guild, "dashboard.choose_the_channel_first"));
        try {
            BanAnnouncementService.TestResult result = banService.sendTest(guild, actor.getUser(), config);
            log.info("Dashboard: {} sent a ban announcement test in #{}", actor.getId(), result.channelName());
            return DataObject.empty().put("sent", true).put("channelName", result.channelName());
        } catch (IllegalStateException e) {
            throw new ApiError(400, e.getMessage());
        }
    }

    /** The dashboard twin of /announce: same rules, judged against the signed-in admin's own permissions in that channel. */
    DataObject send(Guild guild, Member actor, DataObject body) {
        String rawChannel = body.isNull("channelId") ? "" : body.getString("channelId", "").strip();
        if (!rawChannel.matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.choose_a_channel"));
        Long roleId = null;
        if (!body.isNull("roleId")) {
            String rawRole = body.getString("roleId", "").strip();
            if (!rawRole.isEmpty()) {
                if (!rawRole.matches("\\d{1,20}")) throw new ApiError(400, Text.of(guild, "dashboard.that_role_isn_t_valid"));
                roleId = Long.parseLong(rawRole);
            }
        }
        AnnounceService.Prepared prepared;
        try {
            prepared = announceService.prepare(guild, actor, Long.parseLong(rawChannel), body.getString("message", ""),
                    body.getBoolean("everyone", false), body.getBoolean("here", false), roleId);
        } catch (IllegalStateException e) {
            throw new ApiError(400, e.getMessage());
        }
        // queued, so the dashboard answers at once; AnnounceJob posts it and the page follows the job (never posted twice on an unclear failure)
        var plan = prepared.plan();
        DiscordJob job = outbox.enqueue(AnnounceJob.KIND, guild.getIdLong(), actor.getIdLong(), null,
                AnnounceJob.payload(prepared.channel().getIdLong(), plan.content(), plan.parse(), plan.roleIds()));
        log.info("Dashboard: {} queued an announcement for #{} (job {})", actor.getId(), prepared.channel().getName(), job.id());
        return DataObject.empty().put("sent", false).put("queued", true).put("channelName", prepared.channel().getName()).put("job", job.ticket());
    }

    private WelcomeConfig parseAndCheck(Guild guild, DataObject body) {
        // a ban has no DM copy and no link button
        WelcomeConfig parsed = WelcomeAdminApi.parse(guild.getIdLong(), body);
        WelcomeConfig c = new WelcomeConfig(parsed.guildId(), parsed.enabled(), parsed.messageType(), parsed.channelId(), false, parsed.content(), parsed.color(),
                parsed.title(), parsed.titleUrl(), parsed.description(), parsed.authorName(), parsed.authorIconUrl(), parsed.thumbnailUrl(), parsed.imageUrl(),
                parsed.footerText(), parsed.footerIconUrl(), parsed.fields(), false, parsed.linkButtonLabel(), parsed.linkButtonStyle(), parsed.footerStyle());
        List<String> problems = new ArrayList<>(problemsOf(c));
        if (c.channelId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, c.channelId());
            if (channel == null) problems.add("That channel doesn't exist in this server.");
            else if (!channel.canTalk()) problems.add("The bot can't post in #" + channel.getName() + ". It needs permission to view and send messages (and embed links) there.");
        }
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_ban_announcement_can_t_be"), problems);
        return c;
    }

    /** The welcome's checks on the message, worded for this feature (and without the welcome's channel rule, which is checked by the caller). */
    static List<String> problemsOf(WelcomeConfig c) {
        List<String> problems = new ArrayList<>();
        for (String p : WelcomeValidator.validate(c)) {
            problems.add(p.replace("Choose the welcome channel before turning the welcome on.", "Choose the channel before turning the ban announcement on."));
        }
        return problems;
    }
}
