package com.younglings.bot.commandchannel;

import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongToIntFunction;
import java.util.stream.Collectors;

/**
 * Command-only channels: in a configured channel, a regular message from anyone the rule covers is
 * deleted and answered with a short notice (see {@link CommandChannelListener}). This class owns
 * the rules themselves and the cached channel-to-rule lookup that listener hits on every message in
 * the server.
 * <p>
 * Two groups, "Default" and "Custom", always exist once the panel has been opened (see
 * {@link #ensureDefaultGroups}) and can't be deleted: Default always uses the built-in notice, Custom
 * starts with it but is meant to be edited. Any number of further groups can be added, each with its
 * own channels, notice, and rules.
 */
@BService
public class CommandChannelService {
    public static final String DEFAULT_GROUP = "Default";
    public static final String CUSTOM_GROUP = "Custom";
    public static final String DEFAULT_MESSAGE = "You can only use bot commands here!";

    public static final int MAX_GROUPS = 20;
    public static final int MAX_CHANNELS_PER_GROUP = 25; // the "remove a channel" dropdown holds 25 options
    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_MESSAGE_LENGTH = 300;

    public enum AddChannelStatus { ADDED, ALREADY_IN_THIS_GROUP, IN_OTHER_GROUP, GROUP_FULL, NO_SUCH_GROUP }

    public record AddChannelResult(AddChannelStatus status, String otherGroupName) {
        static AddChannelResult of(AddChannelStatus status) {
            return new AddChannelResult(status, null);
        }
    }

    private final CommandChannelRepository repository;

    // guild -> (channel -> its group). Rebuilt lazily after any change, so the per-message lookup is a
    // plain map hit instead of a database query on every message sent anywhere in the server.
    private final Map<Long, Map<Long, CommandChannelGroup>> channelIndexByGuild = new ConcurrentHashMap<>();

    public CommandChannelService(CommandChannelRepository repository) {
        this.repository = repository;
    }

    // --- Lookup (hot path) ---

    /** The group that owns this channel, or {@code null} if it isn't a command-only channel. */
    public CommandChannelGroup findGroup(long guildId, long channelId) {
        return channelIndexByGuild.computeIfAbsent(guildId, this::buildIndex).get(channelId);
    }

    private Map<Long, CommandChannelGroup> buildIndex(long guildId) {
        Map<Long, CommandChannelGroup> index = new HashMap<>();
        for (CommandChannelGroup group : repository.getGroups(guildId)) {
            for (long channelId : group.channelIds()) index.put(channelId, group);
        }
        return Map.copyOf(index);
    }

    private void invalidate(long guildId) {
        channelIndexByGuild.remove(guildId);
    }

    // --- Rules ---

    /**
     * Whether this rule covers a member. The four fields combine like this, in order:
     * <ol>
     *   <li>A member holding any {@code exemptRoleIds} role is <b>never</b> covered — explicit
     *       exemptions always win.</li>
     *   <li>A member whose highest role is at or above {@code exemptFromRoleId} is exempt.</li>
     *   <li>If there is any "applies to" restriction ({@code applyBelowRoleId} and/or
     *       {@code applyRoleIds}), the member is covered only if they match <b>at least one</b> —
     *       highest role below {@code applyBelowRoleId}, <b>or</b> holding an {@code applyRoleIds}
     *       role. With no restriction at all, everyone not exempt is covered.</li>
     * </ol>
     * Fail-safe: if either rank-based role no longer exists, nothing can be compared against it, so the
     * rule covers <b>nobody</b> rather than guessing — better to stop deleting messages than to delete
     * the wrong people's (the panel flags it).
     *
     * @param rolePosition position of a role by id, or {@code -1} if that role no longer exists
     */
    public static boolean appliesTo(CommandChannelGroup group, Set<Long> memberRoleIds, int memberTopPosition,
                                    LongToIntFunction rolePosition) {
        Long belowRole = group.applyBelowRoleId();
        Long exemptFromRole = group.exemptFromRoleId();
        if (belowRole != null && rolePosition.applyAsInt(belowRole) < 0) return false;
        if (exemptFromRole != null && rolePosition.applyAsInt(exemptFromRole) < 0) return false;

        for (long exempt : group.exemptRoleIds()) {
            if (memberRoleIds.contains(exempt)) return false;
        }
        if (exemptFromRole != null && memberTopPosition >= rolePosition.applyAsInt(exemptFromRole)) return false;

        boolean hasApplyRestriction = belowRole != null || !group.applyRoleIds().isEmpty();
        if (!hasApplyRestriction) return true;

        if (belowRole != null && memberTopPosition < rolePosition.applyAsInt(belowRole)) return true;
        for (long apply : group.applyRoleIds()) {
            if (memberRoleIds.contains(apply)) return true;
        }
        return false;
    }

    public boolean appliesTo(CommandChannelGroup group, Member member, Guild guild) {
        Set<Long> roleIds = member.getRoles().stream().map(Role::getIdLong).collect(Collectors.toUnmodifiableSet());
        // @everyone sits at position 0, which is what a member with no other roles is treated as holding.
        int top = member.getRoles().stream().mapToInt(Role::getPosition).max().orElse(0);
        return appliesTo(group, roleIds, top, roleId -> {
            Role role = guild.getRoleById(roleId);
            return role == null ? -1 : role.getPosition();
        });
    }

    /** The notice text for this group — its custom message, or the built-in default if none is set. */
    public static String messageFor(CommandChannelGroup group) {
        String custom = group.customMessage();
        return custom == null || custom.isBlank() ? DEFAULT_MESSAGE : custom;
    }

    /** Plain-English summary of who a group covers, for the panel — includes warnings when a rank role has been deleted. */
    public List<String> describeRules(CommandChannelGroup group, Guild guild) {
        List<String> lines = new ArrayList<>();

        List<String> applies = new ArrayList<>();
        if (group.applyBelowRoleId() != null) applies.add("roles below " + roleLabel(guild, group.applyBelowRoleId()));
        if (!group.applyRoleIds().isEmpty()) applies.add("members with " + roleList(guild, group.applyRoleIds()));
        lines.add("**Applies to:** " + (applies.isEmpty() ? "everyone" : String.join(", or ", applies)));

        List<String> exempt = new ArrayList<>();
        if (group.exemptFromRoleId() != null) exempt.add("roles at or above " + roleLabel(guild, group.exemptFromRoleId()));
        if (!group.exemptRoleIds().isEmpty()) exempt.add("members with " + roleList(guild, group.exemptRoleIds()));
        lines.add("**Exempt:** " + (exempt.isEmpty() ? "nobody" : String.join(", or ", exempt)));

        if (group.applyBelowRoleId() != null && guild.getRoleById(group.applyBelowRoleId()) == null
                || group.exemptFromRoleId() != null && guild.getRoleById(group.exemptFromRoleId()) == null) {
            lines.add("⚠️ A role used above no longer exists, so this group is **paused** (it covers nobody) until you pick a new one.");
        }
        return lines;
    }

    private static String roleLabel(Guild guild, long roleId) {
        return guild.getRoleById(roleId) != null ? "<@&" + roleId + ">" : "*a deleted role*";
    }

    private static String roleList(Guild guild, Set<Long> roleIds) {
        return roleIds.stream().sorted().map(id -> roleLabel(guild, id)).collect(Collectors.joining(", "));
    }

    // --- Mutations (each invalidates the lookup cache) ---

    public static boolean isProtected(CommandChannelGroup group) {
        return group.name().equalsIgnoreCase(DEFAULT_GROUP) || group.name().equalsIgnoreCase(CUSTOM_GROUP);
    }

    public static boolean isDefaultGroup(CommandChannelGroup group) {
        return group.name().equalsIgnoreCase(DEFAULT_GROUP);
    }

    /** Makes sure the fixed "Default" and "Custom" groups exist — safe to call on every panel open. */
    public void ensureDefaultGroups(long guildId) {
        repository.ensureGroup(guildId, DEFAULT_GROUP, null);
        repository.ensureGroup(guildId, CUSTOM_GROUP, DEFAULT_MESSAGE);
        invalidate(guildId);
    }

    public List<CommandChannelGroup> getGroups(long guildId) {
        return repository.getGroups(guildId);
    }

    public Optional<CommandChannelGroup> getGroup(long guildId, long groupId) {
        return repository.getGroup(guildId, groupId);
    }

    /** The new group's id, or {@code -1} if the name is already taken. */
    public long createGroup(long guildId, String name) {
        long id = repository.createGroup(guildId, name.trim());
        invalidate(guildId);
        return id;
    }

    public void deleteGroup(long guildId, long groupId) {
        repository.deleteGroup(guildId, groupId);
        invalidate(guildId);
    }

    public void setEnabled(long guildId, long groupId, boolean enabled) {
        repository.setEnabled(guildId, groupId, enabled);
        invalidate(guildId);
    }

    public void setCustomMessage(long guildId, long groupId, String message) {
        repository.setCustomMessage(guildId, groupId, message);
        invalidate(guildId);
    }

    public void setApplyBelowRole(long guildId, long groupId, Long roleId) {
        repository.setApplyBelowRole(guildId, groupId, roleId);
        invalidate(guildId);
    }

    public void setExemptFromRole(long guildId, long groupId, Long roleId) {
        repository.setExemptFromRole(guildId, groupId, roleId);
        invalidate(guildId);
    }

    public void setApplyRoles(long guildId, long groupId, Set<Long> roleIds) {
        repository.setRoles(guildId, groupId, "APPLY", roleIds);
        invalidate(guildId);
    }

    public void setExemptRoles(long guildId, long groupId, Set<Long> roleIds) {
        repository.setRoles(guildId, groupId, "EXEMPT", roleIds);
        invalidate(guildId);
    }

    public AddChannelResult addChannel(long guildId, long groupId, long channelId) {
        Optional<CommandChannelGroup> target = repository.getGroup(guildId, groupId);
        if (target.isEmpty()) return AddChannelResult.of(AddChannelStatus.NO_SUCH_GROUP);
        if (target.get().channelIds().contains(channelId)) return AddChannelResult.of(AddChannelStatus.ALREADY_IN_THIS_GROUP);
        if (target.get().channelIds().size() >= MAX_CHANNELS_PER_GROUP) return AddChannelResult.of(AddChannelStatus.GROUP_FULL);

        Optional<Long> owner = repository.findGroupIdForChannel(guildId, channelId);
        if (owner.isPresent()) {
            String ownerName = repository.getGroup(guildId, owner.get()).map(CommandChannelGroup::name).orElse("another group");
            return new AddChannelResult(AddChannelStatus.IN_OTHER_GROUP, ownerName);
        }

        repository.addChannel(guildId, groupId, channelId);
        invalidate(guildId);
        return AddChannelResult.of(AddChannelStatus.ADDED);
    }

    public void removeChannel(long guildId, long groupId, long channelId) {
        repository.removeChannel(guildId, groupId, channelId);
        invalidate(guildId);
    }
}
