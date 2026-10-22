package com.younglings.bot.commands.customtag;

import com.younglings.bot.customtag.CustomTag;
import com.younglings.bot.customtag.CustomTag.Delivery;
import com.younglings.bot.customtag.CustomTagRepository;
import com.younglings.bot.customtag.CustomTagVariables;
import com.younglings.bot.discord.Containers;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.permission.AdminRoleFilter;
import io.github.freya022.botcommands.api.commands.annotations.Command;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.JDASlashCommand;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.SlashOption;
import io.github.freya022.botcommands.api.commands.application.slash.annotations.TopLevelSlashCommandData;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.AutocompleteMode;
import io.github.freya022.botcommands.api.commands.application.slash.autocomplete.annotations.AutocompleteHandler;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code /tag use|list|create|delete}: the server's own named replies. Every tag lives under this one command, so the server's slash command list never grows. A server
 * switches tags on in the dashboard (Custom tags page, off until then) where the full editor is; {@code create} and {@code delete} here are for staff who prefer
 * Discord and make plain text tags. A tag never pings anybody: nothing it says is allowed to mention anyone.
 */
@Command
public class TagCommand {
    static final String TAGS = "custom-tag-names";

    private final CustomTagRepository repository;
    private final AdminRoleFilter adminRoleFilter;
    private final Map<String, Long> lastUsed = new ConcurrentHashMap<>();

    public TagCommand(CustomTagRepository repository, AdminRoleFilter adminRoleFilter) {
        this.repository = repository;
        this.adminRoleFilter = adminRoleFilter;
    }

    private boolean isStaff(Guild guild, Member member) {
        return member != null && (member.hasPermission(Permission.MANAGE_SERVER) || adminRoleFilter.isAuthorized(guild, member));
    }

    /** Why this member can't use the tag now, or null when they can. Pure apart from the clock, so it can be tested. */
    static String refusal(CustomTag tag, Set<Long> memberRoles, boolean staff, Long lastUsedMillis, long now) {
        if (!tag.enabled()) return "That tag is switched off.";
        if (!tag.allowedRoles().isEmpty() && !staff && memberRoles.stream().noneMatch(tag.allowedRoles()::contains)) return "You don't have a role that can use that tag.";
        if (!staff && tag.cooldownSeconds() > 0 && lastUsedMillis != null && now - lastUsedMillis < tag.cooldownSeconds() * 1000L) {
            long wait = (tag.cooldownSeconds() * 1000L - (now - lastUsedMillis) + 999) / 1000;
            return "Give it " + wait + " more second" + (wait == 1 ? "" : "s") + " before using that tag again.";
        }
        return null;
    }

    private boolean off(GuildSlashEvent event) {
        if (repository.enabled(event.getGuild().getIdLong())) return false;
        Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "customtag.tags_aren_t_switched_on_in"));
        return true;
    }

    @TopLevelSlashCommandData(description = "Bring up one of this server's saved replies")
    @JDASlashCommand(name = "tag", subcommand = "use", description = "Post a saved reply")
    public void onUse(GuildSlashEvent event,
                      @SlashOption(description = "The tag's name", autocomplete = TAGS) String name,
                      @SlashOption(description = "Anything the tag can use as {args}, {arg1}, {arg2}...") @Nullable String args) {
        if (off(event)) return;
        Guild guild = event.getGuild();
        CustomTag tag = repository.byName(guild.getIdLong(), name).orElse(null);
        if (tag == null) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "customtag.there_no_tag_called_tag_list", CustomTag.normalise(name)));
            return;
        }
        Member member = event.getMember();
        boolean staff = isStaff(guild, member);
        String key = tag.id() + ":" + event.getUser().getIdLong();
        long now = System.currentTimeMillis();
        String why = refusal(tag, member == null ? Set.of() : member.getRoles().stream().map(r -> r.getIdLong()).collect(java.util.stream.Collectors.toSet()), staff, lastUsed.get(key), now);
        if (why != null) {
            Containers.replyEphemeral(event, Containers.WARNING, why);
            return;
        }
        lastUsed.put(key, now);
        repository.countUse(guild.getIdLong(), tag.id());

        String text = CustomTagVariables.fill(tag.content(), new CustomTagVariables.Context(event.getUser().getAsMention(), event.getUser().getEffectiveName(), guild.getName(),
                event.getChannel().getAsMention(), args));
        boolean embed = tag.embed();
        EmbedBuilder builder = new EmbedBuilder().setDescription(text).setColor(tag.embedColor() == null ? new Color(0x5865F2) : new Color(tag.embedColor()));
        if (!tag.embedTitle().isBlank()) builder.setTitle(CustomTagVariables.fill(tag.embedTitle(), new CustomTagVariables.Context(event.getUser().getAsMention(), event.getUser().getEffectiveName(), guild.getName(),
                event.getChannel().getAsMention(), args)));

        if (tag.delivery() == Delivery.DM) {
            event.deferReply(true).queue();
            var dm = event.getUser().openPrivateChannel().flatMap(c -> embed ? c.sendMessageEmbeds(builder.build()) : c.sendMessage(text).setAllowedMentions(List.of()));
            dm.queue(ok -> event.getHook().editOriginal("Sent you **" + tag.name() + "** by direct message.").queue(),
                    error -> event.getHook().editOriginal("I couldn't message you. Open your direct messages from this server and try again.").queue());
            return;
        }
        boolean ephemeral = tag.delivery() == Delivery.PRIVATE;
        if (embed) event.replyEmbeds(builder.build()).setEphemeral(ephemeral).setAllowedMentions(List.of()).queue();
        else event.reply(text).setEphemeral(ephemeral).setAllowedMentions(List.of()).queue();
    }

    @JDASlashCommand(name = "tag", subcommand = "list", description = "See the tags you can use")
    public void onList(GuildSlashEvent event) {
        if (off(event)) return;
        Guild guild = event.getGuild();
        Member member = event.getMember();
        boolean staff = isStaff(guild, member);
        Set<Long> roles = member == null ? Set.of() : member.getRoles().stream().map(r -> r.getIdLong()).collect(java.util.stream.Collectors.toSet());
        List<String> names = repository.forGuild(guild.getIdLong()).stream()
                .filter(t -> t.enabled() && (staff || t.allowedRoles().isEmpty() || roles.stream().anyMatch(t.allowedRoles()::contains))).map(CustomTag::name).toList();
        if (names.isEmpty()) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "customtag.this_server_has_no_tags_you"));
            return;
        }
        Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "customtag.tags"), "`" + String.join("`, `", names) + "`", Text.of(event, "customtag.tag_use_name_posts_one"));
    }

    @JDASlashCommand(name = "tag", subcommand = "create", description = "Staff: make a plain text tag (the dashboard has the full editor)")
    public void onCreate(GuildSlashEvent event,
                         @SlashOption(description = "The tag's name: letters, numbers, - and _") String name,
                         @SlashOption(description = "What it says. Variables: {user} {username} {server} {channel} {args} {arg1}") String text) {
        if (off(event)) return;
        Guild guild = event.getGuild();
        if (!isStaff(guild, event.getMember())) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "customtag.only_staff_can_make_tags"));
            return;
        }
        String clean = CustomTag.normalise(name);
        if (!CustomTag.validName(clean)) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "customtag.a_tag_name_is_up_to", CustomTag.MAX_NAME));
            return;
        }
        if (text.isBlank() || text.length() > CustomTag.MAX_CONTENT) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "customtag.a_tag_text_is_1_to", CustomTag.MAX_CONTENT));
            return;
        }
        if (repository.byName(guild.getIdLong(), clean).isPresent()) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "customtag.there_already_a_tag_called_change", clean));
            return;
        }
        if (repository.forGuild(guild.getIdLong()).size() >= CustomTag.MAX_PER_GUILD) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "customtag.a_server_can_have_at_most", CustomTag.MAX_PER_GUILD));
            return;
        }
        repository.save(new CustomTag(null, guild.getIdLong(), clean, text.strip(), false, "", null, Delivery.CHANNEL, Set.of(), 0, true, 0));
        Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "customtag.made_the_tag_try_tag_use", clean, clean));
    }

    @JDASlashCommand(name = "tag", subcommand = "delete", description = "Staff: delete a tag")
    public void onDelete(GuildSlashEvent event, @SlashOption(description = "The tag to delete", autocomplete = TAGS) String name) {
        if (off(event)) return;
        Guild guild = event.getGuild();
        if (!isStaff(guild, event.getMember())) {
            Containers.replyEphemeral(event, Containers.WARNING, Text.of(event, "customtag.only_staff_can_delete_tags"));
            return;
        }
        CustomTag tag = repository.byName(guild.getIdLong(), name).orElse(null);
        if (tag == null) {
            Containers.replyEphemeral(event, Containers.INFO, Text.of(event, "customtag.there_no_tag_called", CustomTag.normalise(name)));
            return;
        }
        repository.delete(guild.getIdLong(), tag.id());
        Containers.replyEphemeral(event, Containers.SUCCESS, Text.of(event, "customtag.deleted_the_tag", tag.name()));
    }

    @AutocompleteHandler(value = TAGS, mode = AutocompleteMode.FUZZY, showUserInput = false)
    public List<String> suggest(CommandAutoCompleteInteractionEvent event) {
        Guild guild = event.getGuild();
        if (guild == null || !repository.enabled(guild.getIdLong())) return List.of();
        return repository.forGuild(guild.getIdLong()).stream().filter(CustomTag::enabled).map(CustomTag::name).limit(25).toList();
    }
}
