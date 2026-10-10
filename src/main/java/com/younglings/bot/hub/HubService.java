package com.younglings.bot.hub;

import com.younglings.bot.permission.AdminRoleFilter;
import com.younglings.bot.permission.PermissionGroupService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * The Hub's settings and the rule they add up to. Every Hub command has the same three generic settings (on or off, who may
 * use it, where members may use it) enforced by {@link HubCommandFilter}; {@link #decide} is that rule on its own so it can be
 * tested without Discord. A command with no saved settings behaves exactly as it always has.
 * <p>
 * Admins (the Admin permission group) are exempt from the "where" limit, so they can never lock themselves out of the channel
 * they manage a command from, but a command switched off is off for them too.
 */
@BService
public class HubService {
    /** What {@link #decide} concluded, and what to tell the person if the answer was no. */
    public record Decision(boolean allowed, String message) {
        static final Decision ALLOW = new Decision(true, null);

        static Decision deny(String message) {
            return new Decision(false, message);
        }
    }

    /** Settings only Signups has, stored in {@link HubSettings#extras()}. */
    public record SignupPolicy(Long adminChannelId, boolean lockAdminChannel, List<Long> publicChannelIds) {
        public static final SignupPolicy NONE = new SignupPolicy(null, false, List.of());
    }

    private final HubRepository repository;
    private final AdminRoleFilter adminRoleFilter;
    private final PermissionGroupService permissionGroups;
    private final ConcurrentHashMap<Long, Map<String, HubSettings>> cache = new ConcurrentHashMap<>();

    public HubService(HubRepository repository, AdminRoleFilter adminRoleFilter, PermissionGroupService permissionGroups) {
        this.repository = repository;
        this.adminRoleFilter = adminRoleFilter;
        this.permissionGroups = permissionGroups;
    }

    // ---------- settings ----------

    public HubSettings get(long guildId, HubCommand command) {
        return cache.computeIfAbsent(guildId, repository::all).getOrDefault(command.key(), HubSettings.defaults(guildId, command.key()));
    }

    public void save(HubSettings settings) {
        repository.save(settings);
        cache.remove(settings.guildId());
    }

    // ---------- the rule ----------

    /** Whether {@code member} may run {@code command} in {@code channelId} (or, in a thread, under {@code parentChannelId}). */
    public Decision check(Guild guild, Member member, HubCommand command, long channelId, Long parentChannelId) {
        HubSettings settings = get(guild.getIdLong(), command);
        return decide(command, settings, adminRoleFilter.isAuthorized(guild, member), channelId, parentChannelId,
                () -> settings.allowedRefs().stream().anyMatch(ref -> permissionGroups.matches(guild, member, ref)));
    }

    static Decision decide(HubCommand command, HubSettings settings, boolean admin, long channelId, Long parentChannelId, BooleanSupplier inAllowedList) {
        String slash = "/" + command.slashName();
        if (!settings.enabled()) return Decision.deny("`" + slash + "` is turned off in this server.");
        if (admin) return Decision.ALLOW;

        if (!settings.channelIds().isEmpty() && !settings.channelIds().contains(channelId)
                && (parentChannelId == null || !settings.channelIds().contains(parentChannelId))) {
            String where = settings.channelIds().stream().map(id -> "<#" + id + ">").collect(Collectors.joining(", "));
            return Decision.deny("Use `" + slash + "` in " + where + ".");
        }

        if (settings.customAccess()) {
            return inAllowedList.getAsBoolean() ? Decision.ALLOW : Decision.deny("You don't have access to `" + slash + "` in this server.");
        }
        if (command.adminByDefault()) return Decision.deny("You need the Admin role (or higher) to use this command.");
        return Decision.ALLOW;
    }

    // ---------- Signups ----------

    public SignupPolicy signupPolicy(long guildId) {
        return parseSignup(get(guildId, HubCommand.SIGNUP).extras());
    }

    /** The admin channel a new signup must use: the server's fixed one if it has locked it, otherwise whatever the person picked. */
    public long adminChannelFor(long guildId, long picked) {
        SignupPolicy policy = signupPolicy(guildId);
        return policy.lockAdminChannel() && policy.adminChannelId() != null ? policy.adminChannelId() : picked;
    }

    /** Why a public panel can't go in this channel, or null if it can. An empty list means anywhere. */
    public String publicChannelProblem(long guildId, long channelId) {
        List<Long> allowed = signupPolicy(guildId).publicChannelIds();
        if (allowed.isEmpty() || allowed.contains(channelId)) return null;
        return "Signup panels can only be posted in " + allowed.stream().map(id -> "<#" + id + ">").collect(Collectors.joining(", ")) + " in this server.";
    }

    static SignupPolicy parseSignup(String extras) {
        if (extras == null || extras.isBlank()) return SignupPolicy.NONE;
        DataObject json;
        try {
            json = DataObject.fromJson(extras);
        } catch (RuntimeException e) {
            return SignupPolicy.NONE;
        }
        Long admin = null;
        if (!json.isNull("adminChannelId")) {
            try {
                admin = Long.parseLong(json.getString("adminChannelId", ""));
            } catch (NumberFormatException ignored) {
                // a value that isn't an id is treated as not set
            }
        }
        List<Long> publics = new ArrayList<>();
        if (!json.isNull("publicChannelIds")) {
            DataArray array = json.getArray("publicChannelIds");
            for (int i = 0; i < array.length(); i++) {
                try {
                    publics.add(Long.parseLong(array.getString(i, "")));
                } catch (NumberFormatException ignored) {
                    // skip anything that isn't an id
                }
            }
        }
        return new SignupPolicy(admin, json.getBoolean("lockAdminChannel", false), List.copyOf(publics));
    }

    public static String writeSignup(SignupPolicy policy) {
        DataArray publics = DataArray.empty();
        policy.publicChannelIds().forEach(id -> publics.add(Long.toString(id)));
        return DataObject.empty()
                .put("adminChannelId", policy.adminChannelId() == null ? null : Long.toString(policy.adminChannelId()))
                .put("lockAdminChannel", policy.lockAdminChannel())
                .put("publicChannelIds", publics)
                .toString();
    }
}
