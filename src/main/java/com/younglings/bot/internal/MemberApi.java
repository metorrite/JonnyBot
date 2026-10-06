package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.commands.poll.PollOption;
import com.younglings.bot.commands.poll.PollService;
import com.younglings.bot.commands.poll.PollSession;
import com.younglings.bot.commands.signup.SignupService;
import com.younglings.bot.commands.signup.SignupSession;
import com.younglings.bot.commands.signup.SubmissionField;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.permission.MemberAccess;
import com.younglings.bot.member.MemberProfileRepository.Goal;
import com.younglings.bot.member.MemberProfileRepository.Profile;
import com.younglings.bot.member.MemberProfileRepository.SelfRole;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import com.younglings.bot.runescape.RuneScapeSkillCatalog;
import com.younglings.bot.runescape.RuneScapeXpTable;
import com.younglings.bot.runescape.SkillValue;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a logged-in member does to their own record from the website, under {@code /internal/me/}: profile
 * text and privacy, DM preferences, skill goals, and the self-assignable roles. Like the older nickname and
 * colour endpoints, these take a {@code userId} and trust it — the website must source it only from the
 * visitor's verified login session, never from anything the browser sends. Each call only ever touches that
 * one user's own rows (or their own roles).
 */
@BService
public class MemberApi {
    private static final Logger log = LoggerFactory.getLogger(MemberApi.class);
    private static final String PREFIX = "/internal/me/";
    private static final int MAX_BIO = 280;
    private static final int MAX_GOALS = 12;
    private static final Pattern HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** A role carrying any of these must never be self-assignable, whatever an admin picks. */
    static final EnumSet<Permission> DANGEROUS = EnumSet.of(Permission.ADMINISTRATOR, Permission.MANAGE_SERVER, Permission.MANAGE_ROLES,
            Permission.MANAGE_CHANNEL, Permission.KICK_MEMBERS, Permission.BAN_MEMBERS, Permission.MODERATE_MEMBERS, Permission.MESSAGE_MANAGE,
            Permission.MESSAGE_MENTION_EVERYONE, Permission.MANAGE_WEBHOOKS, Permission.MANAGE_PERMISSIONS, Permission.NICKNAME_MANAGE,
            Permission.VOICE_MUTE_OTHERS, Permission.VOICE_DEAF_OTHERS, Permission.VOICE_MOVE_OTHERS, Permission.VIEW_AUDIT_LOGS,
            Permission.MANAGE_EVENTS, Permission.CREATE_GUILD_EXPRESSIONS, Permission.MANAGE_GUILD_EXPRESSIONS);

    private final MemberProfileRepository repository;
    private final PlayerLinkRepository links;
    private final PollService pollService;
    private final SignupService signupService;
    private final MemberAccess memberAccess;

    public MemberApi(MemberProfileRepository repository, PlayerLinkRepository links, PollService pollService, SignupService signupService, MemberAccess memberAccess) {
        this.repository = repository;
        this.links = links;
        this.pollService = pollService;
        this.signupService = signupService;
        this.memberAccess = memberAccess;
    }

    /** A failure with the status and message the website should show. */
    private static final class Rejected extends RuntimeException {
        final int status;

        Rejected(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** The dangerous permissions among {@code granted} — empty means the role is safe to hand out. */
    static EnumSet<Permission> dangerousIn(Collection<Permission> granted) {
        EnumSet<Permission> found = EnumSet.noneOf(Permission.class);
        for (Permission p : granted) if (DANGEROUS.contains(p)) found.add(p);
        return found;
    }

    /** Whether the bot may safely add/remove {@code role} for members: an ordinary role, below the bot's own, with nothing powerful in it. */
    static boolean safeToAssign(Guild guild, Role role) {
        return !role.isPublicRole() && !role.isManaged() && guild.getSelfMember().canInteract(role) && dangerousIn(role.getPermissions()).isEmpty();
    }

    public void handle(HttpExchange exchange, Guild guild) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String route = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
            if (route.endsWith("/")) route = route.substring(0, route.length() - 1);
            String method = exchange.getRequestMethod().toUpperCase();

            DataObject body = method.equals("GET") || method.equals("DELETE") ? DataObject.empty() : readBody(exchange);
            long userId = parseId(method.equals("GET") || method.equals("DELETE") ? query(exchange, "userId") : body.getString("userId", null), "userId");

            DataObject result = switch (route) {
                case "settings" -> method.equals("GET") ? getSettings(guild, userId) : method.equals("PUT") ? saveSettings(guild, userId, body) : null;
                case "goals" -> switch (method) {
                    case "GET" -> goals(guild, userId);
                    case "POST" -> addGoal(guild, userId, body);
                    case "DELETE" -> deleteGoal(guild, userId, parseId(query(exchange, "id"), "id"));
                    default -> null;
                };
                case "roles" -> method.equals("GET") ? roles(guild, userId) : method.equals("POST") ? toggleRole(guild, userId, body) : null;
                case "polls" -> method.equals("GET") ? myPolls(guild, userId) : null;
                case "polls/vote" -> method.equals("POST") ? votePoll(guild, userId, body) : null;
                case "signups" -> method.equals("GET") ? mySignups(guild, userId) : null;
                case "signups/join" -> method.equals("POST") ? joinSignup(guild, userId, body) : null;
                case "signups/leave" -> method.equals("POST") ? leaveSignup(guild, userId, body) : null;
                default -> null;
            };

            if (result == null) {
                InternalApiServer.sendJson(exchange, route.isEmpty() ? 404 : 405, DataObject.empty().put("error", "Not found or method not allowed"));
            } else {
                InternalApiServer.sendJson(exchange, 200, result);
            }
        } catch (Rejected e) {
            InternalApiServer.sendJson(exchange, e.status, DataObject.empty().put("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Member API request {} failed", exchange.getRequestURI().getPath(), e);
            InternalApiServer.sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    // ---------- settings ----------

    private DataObject settingsJson(Profile p) {
        return DataObject.empty()
                .put("bio", p.bio()).put("accentColor", p.accentColor()).put("pinnedSkill", p.pinnedSkill())
                .put("hideAdventureLog", p.hideAdventureLog()).put("hideFromLeaderboards", p.hideFromLeaderboards())
                .put("dmGoals", p.dmGoals()).put("dmEvents", p.dmEvents());
    }

    private DataObject getSettings(Guild guild, long userId) {
        DataArray rsns = DataArray.empty();
        links.getLinksForUser(guild.getIdLong(), userId).forEach(l -> rsns.add(l.rsn()));
        return settingsJson(repository.getProfile(guild.getIdLong(), userId)).put("rsns", rsns);
    }

    private DataObject saveSettings(Guild guild, long userId, DataObject body) {
        Profile current = repository.getProfile(guild.getIdLong(), userId);

        String bio = body.getString("bio", current.bio()).replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
        if (bio.length() > MAX_BIO) throw new Rejected(400, "Your bio can be at most " + MAX_BIO + " characters.");

        String accent = body.isNull("accentColor") ? null : body.getString("accentColor").strip();
        if (accent != null && accent.isEmpty()) accent = null;
        if (accent != null && !HEX.matcher(accent).matches()) throw new Rejected(400, "The accent colour must look like #d4af37.");
        if (accent != null) accent = accent.toLowerCase();

        Integer pinned = null;
        if (!body.isNull("pinnedSkill")) {
            int id = body.getInt("pinnedSkill");
            if (id < 0 || id >= RuneScapeSkillCatalog.skillCount()) throw new Rejected(400, "That isn't a skill.");
            pinned = id;
        }

        repository.saveProfile(new Profile(guild.getIdLong(), userId, bio, accent, pinned,
                body.getBoolean("hideAdventureLog", current.hideAdventureLog()), body.getBoolean("hideFromLeaderboards", current.hideFromLeaderboards()),
                body.getBoolean("dmGoals", current.dmGoals()), body.getBoolean("dmEvents", current.dmEvents())));
        return getSettings(guild, userId);
    }

    // ---------- goals ----------

    private Map<Integer, SkillValue> latestSkills(Guild guild, String rsn) {
        var snapshot = links.getLatestSnapshot(guild.getIdLong(), rsn);
        Map<Integer, SkillValue> skills = new HashMap<>();
        if (snapshot != null) links.getSkillsForSnapshot(snapshot.snapshotId()).forEach(s -> skills.put(s.skillId(), s));
        return skills;
    }

    private DataObject goals(Guild guild, long userId) {
        Map<String, Map<Integer, SkillValue>> byRsn = new HashMap<>();
        DataArray array = DataArray.empty();
        for (Goal goal : repository.goalsFor(guild.getIdLong(), userId)) {
            SkillValue now = byRsn.computeIfAbsent(goal.rsn().toLowerCase(), k -> latestSkills(guild, goal.rsn())).get(goal.skillId());
            long xp = now == null ? 0 : now.xp();
            long startXp = RuneScapeXpTable.xpForLevel(goal.skillId(), Math.max(1, goal.targetLevel() - 1));
            long targetXp = RuneScapeXpTable.xpForLevel(goal.skillId(), goal.targetLevel());
            array.add(DataObject.empty()
                    .put("id", Long.toString(goal.id())).put("skillId", goal.skillId()).put("skill", RuneScapeSkillCatalog.nameFor(goal.skillId()))
                    .put("targetLevel", goal.targetLevel()).put("targetXp", targetXp).put("currentXp", xp).put("currentLevel", now == null ? null : now.level())
                    .put("xpRemaining", Math.max(0, targetXp - xp))
                    .put("progress", goal.achievedAt() != null ? 1.0 : targetXp <= startXp ? 0.0 : Math.max(0, Math.min(1, (double) (xp - startXp) / (targetXp - startXp))))
                    .put("rsn", goal.rsn()).put("createdAt", goal.createdAt().toString()).put("achievedAt", goal.achievedAt() == null ? null : goal.achievedAt().toString()));
        }
        return DataObject.empty().put("goals", array);
    }

    private DataObject addGoal(Guild guild, long userId, DataObject body) {
        List<PlayerLink> linked = links.getLinksForUser(guild.getIdLong(), userId);
        if (linked.isEmpty()) throw new Rejected(409, "Link your RuneScape name with /rs in Discord first — goals track your linked account.");

        int skillId = body.getInt("skillId", -1);
        int target = body.getInt("targetLevel", -1);
        if (skillId < 0 || skillId >= RuneScapeSkillCatalog.skillCount()) throw new Rejected(400, "Choose a skill.");
        if (target < 2 || target > 120) throw new Rejected(400, "The target level must be between 2 and 120.");

        PlayerLink link = linked.getFirst();
        SkillValue now = latestSkills(guild, link.rsn()).get(skillId);
        if (now != null && now.level() >= target) throw new Rejected(400, "You're already level " + now.level() + " in " + RuneScapeSkillCatalog.nameFor(skillId) + ".");

        long active = repository.goalsFor(guild.getIdLong(), userId).stream().filter(g -> g.achievedAt() == null).count();
        if (active >= MAX_GOALS) throw new Rejected(409, "You can have up to " + MAX_GOALS + " active goals — finish or remove one first.");

        if (repository.addGoal(guild.getIdLong(), userId, link.rsn(), skillId, target) < 0) throw new Rejected(409, "You already have that goal.");
        return goals(guild, userId);
    }

    private DataObject deleteGoal(Guild guild, long userId, long goalId) {
        if (!repository.deleteGoal(guild.getIdLong(), userId, goalId)) throw new Rejected(404, "That goal doesn't exist.");
        return goals(guild, userId);
    }

    // ---------- self-assignable roles ----------

    private DataObject roles(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        if (member == null) {
            try {
                member = guild.retrieveMemberById(userId).complete();
            } catch (Exception e) {
                throw new Rejected(404, "You're not in the server.");
            }
        }
        DataArray array = DataArray.empty();
        for (SelfRole configured : repository.selfRoles(guild.getIdLong())) {
            Role role = guild.getRoleById(configured.roleId());
            if (role == null || !safeToAssign(guild, role)) continue;
            array.add(DataObject.empty().put("roleId", Long.toString(role.getIdLong())).put("name", role.getName())
                    .put("label", configured.label() == null || configured.label().isBlank() ? role.getName() : configured.label())
                    .put("description", configured.description()).put("color", role.getColors().getPrimaryRaw() & 0xFFFFFF)
                    .put("has", member.getRoles().contains(role)));
        }
        return DataObject.empty().put("roles", array);
    }

    private DataObject toggleRole(Guild guild, long userId, DataObject body) {
        long roleId = parseId(body.getString("roleId", null), "roleId");
        boolean on = body.getBoolean("on", true);

        if (repository.selfRoles(guild.getIdLong()).stream().noneMatch(r -> r.roleId() == roleId)) throw new Rejected(403, "That role isn't self-assignable.");
        Role role = guild.getRoleById(roleId);
        if (role == null) throw new Rejected(404, "That role no longer exists.");
        if (!safeToAssign(guild, role)) throw new Rejected(403, "That role can't be assigned from here.");

        Member member;
        try {
            member = guild.retrieveMemberById(userId).complete();
        } catch (Exception e) {
            throw new Rejected(404, "You're not in the server.");
        }
        try {
            if (on) guild.addRoleToMember(member, role).reason("Self-assigned on the website").complete();
            else guild.removeRoleFromMember(member, role).reason("Removed on the website").complete();
        } catch (Exception e) {
            log.warn("Self-role {} for {} failed", roleId, userId, e);
            throw new Rejected(502, "Discord wouldn't change that role.");
        }
        return roles(guild, userId);
    }

    // ---------- polls ----------

    private Member memberOf(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        if (member != null) return member;
        try {
            return guild.retrieveMemberById(userId).complete();
        } catch (Exception e) {
            throw new Rejected(404, "You're not in the server.");
        }
    }

    /** Which options the member has picked in each active poll. */
    private DataObject myPolls(Guild guild, long userId) {
        DataArray array = DataArray.empty();
        for (PollSession poll : pollService.activePolls(guild.getIdLong())) {
            DataArray mine = DataArray.empty();
            pollService.myOptionNumbers(poll.pollId(), userId).forEach(mine::add);
            array.add(DataObject.empty().put("pollId", Long.toString(poll.pollId())).put("mine", mine));
        }
        return DataObject.empty().put("polls", array);
    }

    /** Votes the way the Discord button does — same rules, same message update — so the site and the poll message always agree. */
    private DataObject votePoll(Guild guild, long userId, DataObject body) {
        long pollId = parseId(body.getString("pollId", null), "pollId");
        int number = body.getInt("optionNumber", -1);

        Member member = memberOf(guild, userId);
        if (!memberAccess.isMemberTier(guild, member)) throw new Rejected(403, "Voting is for verified clan members.");

        PollSession poll = pollService.getSessionById(pollId);
        if (poll == null || poll.guildId() != guild.getIdLong()) throw new Rejected(404, "That poll doesn't exist or has ended.");
        PollOption option = pollService.getOptions(pollId).stream().filter(o -> o.optionNumber() == number).findFirst().orElse(null);
        if (option == null) throw new Rejected(400, "That isn't an option in this poll.");

        if (pollService.toggleVote(pollId, option.optionId(), userId) == PollService.VoteResult.POLL_CLOSED) throw new Rejected(409, "That poll has ended.");
        pollService.updateMessage(guild, pollId);
        return myPolls(guild, userId);
    }

    // ---------- signups ----------

    /** The signup sheets the member is currently on. */
    private DataObject mySignups(Guild guild, long userId) {
        DataArray joined = DataArray.empty();
        for (SignupSession session : signupService.getVisibleSignups(guild.getIdLong())) {
            if (signupService.getEntries(session.signupId()).stream().anyMatch(e -> e.userId() == userId)) joined.add(Long.toString(session.signupId()));
        }
        DataArray rsns = DataArray.empty();
        links.getLinksForUser(guild.getIdLong(), userId).forEach(l -> rsns.add(l.rsn()));
        return DataObject.empty().put("joined", joined).put("rsns", rsns);
    }

    private SignupSession ownSignup(Guild guild, long signupId) {
        SignupSession session = signupService.getSessionById(signupId);
        if (session == null || session.guildId() != guild.getIdLong()) throw new Rejected(404, "That signup doesn't exist any more.");
        return session;
    }

    private DataObject joinSignup(Guild guild, long userId, DataObject body) {
        long signupId = parseId(body.getString("signupId", null), "signupId");
        SignupSession session = ownSignup(guild, signupId);
        memberOf(guild, userId); // must be in the server, like clicking the button there
        if (!signupService.isSignupActive(signupId)) throw new Rejected(409, "This signup is paused right now.");

        String rsn;
        String submission = null;
        switch (session.type()) {
            case QUEUE -> {
                rsn = body.getString("rsn", "").strip();
                if (rsn.isEmpty()) rsn = links.getLinksForUser(guild.getIdLong(), userId).stream().findFirst().map(PlayerLink::rsn).orElse("");
                if (rsn.isEmpty() || rsn.length() > 50) throw new Rejected(400, "Enter your RuneScape name (up to 50 characters).");
            }
            case GROUP -> rsn = String.valueOf(userId);
            case SUBMISSION -> {
                rsn = String.valueOf(userId);
                List<SubmissionField> fields = SubmissionField.deserialize(session.submissionFields());
                DataArray given = body.isNull("fields") ? DataArray.empty() : body.getArray("fields");
                List<String> values = new ArrayList<>();
                for (int i = 0; i < fields.size(); i++) {
                    String value = i < given.length() ? given.getString(i).strip() : "";
                    SubmissionField field = fields.get(i);
                    if (value.length() > 500) throw new Rejected(400, "\"" + field.label() + "\" is too long.");
                    if (field.required() && value.isEmpty()) throw new Rejected(400, "\"" + field.label() + "\" is required.");
                    if (!value.isEmpty() && !field.type().equals(SubmissionField.TYPE_TEXT) && !value.matches("^https?://\\S+$")) {
                        throw new Rejected(400, "\"" + field.label() + "\" needs a link starting with https://.");
                    }
                    values.add(value);
                }
                submission = SubmissionField.serializeValues(values);
            }
            default -> throw new Rejected(400, "That signup type isn't supported here.");
        }

        if (!signupService.addUser(guild, signupId, userId, rsn, submission)) throw new Rejected(409, "You're already signed up, or it's full.");
        signupService.updateMessages(guild, signupId);
        return mySignups(guild, userId);
    }

    private DataObject leaveSignup(Guild guild, long userId, DataObject body) {
        long signupId = parseId(body.getString("signupId", null), "signupId");
        ownSignup(guild, signupId);
        if (!signupService.removeUser(guild, signupId, userId)) throw new Rejected(409, "You aren't on that signup.");
        signupService.updateMessages(guild, signupId);
        return mySignups(guild, userId);
    }

    // ---------- helpers ----------

    private static DataObject readBody(HttpExchange exchange) {
        try (var in = exchange.getRequestBody()) {
            return DataObject.fromJson(in);
        } catch (Exception e) {
            throw new Rejected(400, "The request body isn't valid JSON.");
        }
    }

    private static long parseId(String raw, String name) {
        try {
            long id = Long.parseLong(raw == null ? "" : raw.trim());
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException e) {
            throw new Rejected(400, "\"" + name + "\" is missing or not a valid id.");
        }
    }

    private static String query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
