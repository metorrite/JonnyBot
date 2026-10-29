package com.runeclans.bot.hubsites;

import com.runeclans.bot.discord.Containers;
import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.i18n.Text;
import com.runeclans.bot.internal.MemberApi;
import com.runeclans.bot.runeclans.RuneClansHubClient;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Discord side of the hub's requests in Discord (T-734, runeclans-hub BUILD-A-BOT section 10), as outbox jobs so they run in the process
 * that holds the server's shard and wait out Discord's rate limits:
 * <ul>
 *   <li>{@code request.card}: posts a request's card in the channel the site admin picked, or edits the one already there, and tells the hub
 *   where it is ({@code PUT /v1/requests/{id}/card}). Not repeated after an unclear failure (it may have posted); the next change puts the
 *   card right.</li>
 *   <li>{@code community.role}: gives or takes the community's Discord role when someone joins or leaves the community
 *   ({@code community.discord_role}). Only a role the bot may safely hand out ({@link MemberApi#safeToAssign}).</li>
 * </ul>
 */
@BService
public class RequestCardJobs implements DiscordJobHandler {
    static final String CARD = "request.card";
    static final String ROLE = "community.role";
    private static final EnumSet<Message.MentionType> NO_PINGS = EnumSet.noneOf(Message.MentionType.class);
    private static final int REMEMBERED = 5000;

    private final RuneClansHubClient hub;
    /** The newest rev each request's card shows, so an older change never paints over a newer one. */
    private final Map<String, Long> shown = bounded();
    /** Cards posted here whose place the hub may not know yet: request id to {channelId, messageId}. */
    private final Map<String, String[]> posted = bounded();

    public RequestCardJobs(RuneClansHubClient hub) {
        this.hub = hub;
    }

    @Override
    public String kind() {
        return CARD;
    }

    @Override
    public List<String> kinds() {
        return List.of(CARD, ROLE);
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public boolean idempotent(String kind) {
        return kind.equals(ROLE);
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        return job.kind().equals(ROLE) ? role(DiscordJobs.guild(jda, job), job.payload()) : card(DiscordJobs.guild(jda, job), job.payload());
    }

    private DataObject card(Guild guild, DataObject payload) {
        DataObject request = payload.getObject("request");
        String id = request.getString("id", "");
        long rev = request.getLong("rev", 0);
        Long seen = shown.get(id);
        if (seen != null && seen > rev) return DataObject.empty().put("skipped", "older");
        String channelId = payload.getString("channelId", null);
        if (channelId == null) throw new DiscordJobs.Refused("No channel is picked for requests yet.");
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new DiscordJobs.Refused("The requests channel no longer exists.");
        if (!channel.canTalk()) throw new DiscordJobs.Refused("The bot can't post in #" + channel.getName() + ".");
        Container view = view(RequestCardLogic.card(request, (key, args) -> Text.of(guild, key, args)));
        String messageId = placeIn(request, id, channelId);
        if (messageId != null) {
            try {
                DiscordJobs.send(channel.editMessageComponentsById(messageId, List.of(view)).useComponentsV2(true).setAllowedMentions(NO_PINGS));
                shown.put(id, rev);
                return DataObject.empty().put("messageId", messageId).put("edited", true);
            } catch (ErrorResponseException e) {
                if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MESSAGE) throw e;
                // deleted in Discord: post it again below
            }
        }
        Message sent = DiscordJobs.send(channel.sendMessageComponents(List.of(view)).useComponentsV2(true).setAllowedMentions(NO_PINGS));
        shown.put(id, rev);
        posted.put(id, new String[] {channelId, sent.getId()});
        RuneClansHubClient.Answer told = hub.call("PUT", "/v1/requests/" + id + "/card",
                DataObject.empty().put("channelId", channelId).put("messageId", sent.getId()).toString());
        if (told != null && told.ok()) posted.remove(id);
        return DataObject.empty().put("messageId", sent.getId()).put("edited", false);
    }

    /** The card's message in this channel: as the hub knows it, else as this process posted it; null when there is none yet. */
    private String placeIn(DataObject request, String id, String channelId) {
        DataObject external = request.optObject("external").orElse(null);
        if (external != null && channelId.equals(external.getString("channelId", null))) return external.getString("messageId", null);
        String[] here = posted.get(id);
        return here != null && here[0].equals(channelId) ? here[1] : null;
    }

    private DataObject role(Guild guild, DataObject payload) {
        Role role = guild.getRoleById(payload.getString("roleId"));
        if (role == null) throw new DiscordJobs.Refused("The community's role no longer exists.");
        if (!MemberApi.safeToAssign(guild, role)) throw new DiscordJobs.Refused("The bot can't hand out the community's role.");
        boolean give = payload.getBoolean("give", true);
        Member member;
        try {
            member = DiscordJobs.member(guild, payload.getLong("discordId"));
        } catch (ErrorResponseException e) {
            if (e.getErrorResponse() != ErrorResponse.UNKNOWN_MEMBER) throw e;
            if (!give) return DataObject.empty().put("has", false); // left the server: nothing to take
            throw new DiscordJobs.Refused("They aren't in the server.");
        }
        boolean has = member.getRoles().contains(role);
        if (give && !has) DiscordJobs.send(guild.addRoleToMember(member, role).reason("Joined the community on the website"));
        if (!give && has) DiscordJobs.send(guild.removeRoleFromMember(member, role).reason("Left the community on the website"));
        return DataObject.empty().put("roleId", role.getId()).put("has", give);
    }

    /** A card as a Discord message: its lines, then its buttons (five to a row). */
    static Container view(RequestCardLogic.Card card) {
        List<ContainerChildComponent> children = new ArrayList<>();
        for (String line : card.lines()) children.add(TextDisplay.of(line));
        List<Button> row = new ArrayList<>();
        for (RequestCardLogic.Button b : card.buttons()) {
            RequestCardLogic.Press p = RequestCardLogic.parse(b.id());
            String action = p == null ? "" : p.action();
            row.add(switch (action) {
                case "accepted" -> Button.success(b.id(), b.label());
                case "declined" -> Button.danger(b.id(), b.label());
                default -> b.primary() ? Button.primary(b.id(), b.label()) : Button.secondary(b.id(), b.label());
            });
            if (row.size() == 5) {
                children.add(ActionRow.of(row));
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) children.add(ActionRow.of(row));
        return Containers.card(card.accent(), children);
    }

    private static <V> Map<String, V> bounded() {
        return Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > REMEMBERED;
            }
        });
    }
}
