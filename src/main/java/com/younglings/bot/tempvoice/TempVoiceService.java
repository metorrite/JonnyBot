package com.younglings.bot.tempvoice;

import com.younglings.bot.discord.Shards;
import com.younglings.bot.tempvoice.TempVoiceRules.Hub;
import com.younglings.bot.tempvoice.TempVoiceRules.Temp;
import com.younglings.bot.util.BotScheduler;
import io.github.freya022.botcommands.api.core.annotations.BEventListener;
import io.github.freya022.botcommands.api.core.events.InjectedJDAEvent;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Makes, controls and tidies the temporary voice channels. Joining a hub channel makes the member a channel of their own (named from the hub's pattern, in
 * its category) and moves them into it; the channel is deleted when it empties. Everything is kept in the database, and every few minutes (and at start-up)
 * a sweep deletes empty temporary channels and forgets the ones that were deleted by hand, so nothing is left behind after a restart. Owners change their
 * channel with a panel of buttons posted in the channel's own text chat; they are never given Manage Channel, so they can only do what the panel offers.
 */
@BService
public class TempVoiceService {
    private static final Logger log = LoggerFactory.getLogger(TempVoiceService.class);
    /** What the bot needs in a server to make, move into and manage these channels. */
    static final Permission[] NEEDED = {Permission.VIEW_CHANNEL, Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS, Permission.VOICE_MOVE_OTHERS, Permission.VOICE_CONNECT};

    private final TempVoiceRepository repository;
    private volatile JDA jda;
    private final Map<String, Long> lastCreate = new ConcurrentHashMap<>();
    private final ScheduledExecutorService executor = BotScheduler.lane("temp-voice");

    public TempVoiceService(TempVoiceRepository repository) {
        this.repository = repository;
    }

    @BEventListener
    public void onJdaReady(InjectedJDAEvent event) {
        this.jda = event.getJda();
        executor.scheduleWithFixedDelay(this::sweep, 30, 300, TimeUnit.SECONDS);
    }

    /** Why the bot can't run temporary channels in this server, in words for the person setting it up, or null when it can. */
    public static String problemWith(Guild guild) {
        if (!guild.getSelfMember().hasPermission(NEEDED)) {
            return "The bot needs the Manage Channels, Manage Permissions and Move Members permissions (and to see and connect to the voice channels) to run temporary voice channels.";
        }
        return null;
    }

    // ---------- joining and leaving ----------

    /** A member joined {@code joined}: if it is a hub, give them their channel (or take them back to the one they have). */
    public void onJoin(Member member, net.dv8tion.jda.api.entities.channel.middleman.AudioChannel joined) {
        Hub hub = repository.hubByChannel(joined.getIdLong()).orElse(null);
        if (hub == null || member.getUser().isBot()) return;
        Guild guild = member.getGuild();
        if (problemWith(guild) != null) return;
        var existing = repository.channelOwnedBy(guild.getIdLong(), member.getIdLong());
        if (existing.isPresent()) {
            VoiceChannel channel = guild.getVoiceChannelById(existing.get().channelId());
            if (channel != null) {
                guild.moveVoiceMember(member, channel).queue(ok -> {}, error -> {});
                return;
            }
            repository.deleteChannel(existing.get().channelId()); // it was deleted by hand
        }
        String key = guild.getId() + ":" + member.getId();
        long now = System.currentTimeMillis();
        if (!TempVoiceRules.shouldCreate(false, repository.count(guild.getIdLong()), now, lastCreate.get(key))) return;
        lastCreate.put(key, now);
        if (lastCreate.size() > 5000) lastCreate.values().removeIf(t -> now - t > 60_000);
        create(guild, member, hub, joined);
    }

    private void create(Guild guild, Member owner, Hub hub, net.dv8tion.jda.api.entities.channel.middleman.AudioChannel hubChannel) {
        Category category = hub.categoryId() != null ? guild.getCategoryById(hub.categoryId()) : null;
        if (category == null && hubChannel instanceof VoiceChannel voice) category = voice.getParentCategory();
        String name = TempVoiceRules.channelName(hub.namePattern(), owner.getEffectiveName());
        var action = guild.createVoiceChannel(name, category)
                .addMemberPermissionOverride(owner.getIdLong(), EnumSet.of(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT), EnumSet.noneOf(Permission.class));
        if (hub.userLimit() > 0) action.setUserlimit(hub.userLimit());
        action.queue(channel -> {
            repository.addChannel(new Temp(channel.getIdLong(), guild.getIdLong(), owner.getIdLong(), hub.hubChannelId(), false));
            guild.moveVoiceMember(owner, channel).queue(moved -> postPanel(channel, owner.getIdLong()), error -> {
                // they left the hub before the move: nobody is in it, so it goes straight away
                log.debug("Temporary voice: {} left before being moved, deleting {}", owner.getId(), channel.getId());
                delete(channel.getIdLong(), guild);
            });
        }, error -> log.warn("Temporary voice: could not create a channel in guild {}: {}", guild.getId(), error.getMessage()));
    }

    /** A member left {@code left}: delete it if it is a temporary channel and is now empty. */
    public void onLeave(Guild guild, net.dv8tion.jda.api.entities.channel.middleman.AudioChannel left) {
        if (repository.channel(left.getIdLong()).isEmpty()) return;
        if (TempVoiceRules.deleteWhenEmpty(true, left.getMembers().size())) delete(left.getIdLong(), guild);
    }

    private void delete(long channelId, Guild guild) {
        repository.deleteChannel(channelId);
        VoiceChannel channel = guild.getVoiceChannelById(channelId);
        if (channel != null) channel.delete().reason("Temporary voice channel is empty").queue(ok -> {}, error -> log.debug("Could not delete temporary channel {}: {}", channelId, error.getMessage()));
    }

    /** Deletes empty temporary channels and forgets the ones that no longer exist. Runs at start-up and every few minutes, so a restart loses nothing. */
    void sweep() {
        try {
            JDA jda = this.jda;
            if (jda == null) return;
            for (Temp temp : repository.allChannels()) {
                Guild guild = Shards.guild(jda, temp.guildId());
                if (guild == null) continue;
                VoiceChannel channel = guild.getVoiceChannelById(temp.channelId());
                if (channel == null) {
                    repository.deleteChannel(temp.channelId());
                } else if (channel.getMembers().isEmpty()) {
                    delete(temp.channelId(), guild);
                }
            }
        } catch (Exception e) {
            log.error("Temporary voice sweep failed", e);
        }
    }

    // ---------- the panel and its controls ----------

    static MessageCreateBuilder panel(long channelId, long ownerId) {
        String id = Long.toString(channelId);
        return new MessageCreateBuilder()
                .setContent("**Your voice channel.** <@" + ownerId + "> owns it and can use these buttons. If the owner leaves, someone still in the channel can claim it.")
                .setAllowedMentions(EnumSet.noneOf(net.dv8tion.jda.api.entities.Message.MentionType.class))
                .setComponents(
                        ActionRow.of(Button.secondary("tv:rename:" + id, "Rename"), Button.secondary("tv:limit:" + id, "Limit"),
                                Button.secondary("tv:lock:" + id, "Lock"), Button.secondary("tv:unlock:" + id, "Unlock")),
                        ActionRow.of(Button.secondary("tv:permit:" + id, "Permit"), Button.secondary("tv:kick:" + id, "Kick"),
                                Button.secondary("tv:transfer:" + id, "Transfer"), Button.primary("tv:claim:" + id, "Claim")));
    }

    private void postPanel(VoiceChannel channel, long ownerId) {
        channel.sendMessage(panel(channel.getIdLong(), ownerId).build()).queue(ok -> {}, error -> log.debug("Temporary voice: could not post the panel in {}", channel.getId()));
    }

    /** Renames the channel (cleaned of mentions and cut to 100 characters). Returns the name used. */
    public String rename(VoiceChannel channel, String wanted) {
        String name = TempVoiceRules.cleanName(wanted);
        channel.getManager().setName(name).reason("Temporary voice: renamed by its owner").queue(ok -> {}, error -> {});
        return name;
    }

    public void limit(VoiceChannel channel, int limit) {
        channel.getManager().setUserLimit(limit).reason("Temporary voice: limit changed by its owner").queue(ok -> {}, error -> {});
    }

    public void lock(VoiceChannel channel, boolean locked) {
        var override = channel.upsertPermissionOverride(channel.getGuild().getPublicRole());
        (locked ? override.deny(Permission.VOICE_CONNECT) : override.clear(Permission.VOICE_CONNECT)).queue(ok -> {}, error -> {});
        repository.setLocked(channel.getIdLong(), locked);
    }

    public void permit(VoiceChannel channel, Member target) {
        channel.upsertPermissionOverride(target).grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT).queue(ok -> {}, error -> {});
    }

    /** Disconnects the member and stops them coming back (until they are permitted again). */
    public void kick(VoiceChannel channel, Member target) {
        channel.upsertPermissionOverride(target).deny(Permission.VOICE_CONNECT).queue(ok -> {}, error -> {});
        if (channel.getMembers().contains(target)) channel.getGuild().kickVoiceMember(target).queue(ok -> {}, error -> {});
    }

    /** Hands the channel to another member: they get the owner's access, the old owner keeps only what everyone has. */
    public void transfer(VoiceChannel channel, long oldOwner, Member newOwner) {
        repository.setOwner(channel.getIdLong(), newOwner.getIdLong());
        channel.upsertPermissionOverride(newOwner).grant(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT).queue(ok -> {}, error -> {});
        Member old = channel.getGuild().getMemberById(oldOwner);
        if (old != null) channel.upsertPermissionOverride(old).clear(Permission.VIEW_CHANNEL, Permission.VOICE_CONNECT).queue(ok -> {}, error -> {});
        postPanel(channel, newOwner.getIdLong());
    }

    /** Whether the member is in the channel right now. */
    public static boolean inChannel(VoiceChannel channel, long userId) {
        return channel.getMembers().stream().anyMatch(m -> m.getIdLong() == userId);
    }

    public List<Temp> channels(long guildId) {
        return repository.channels(guildId);
    }
}
