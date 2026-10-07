package com.younglings.bot.internal;

import com.sun.net.httpserver.HttpExchange;
import com.younglings.bot.commands.ticket.HelpOnboarding;
import com.younglings.bot.commands.ticket.HelpRules;
import com.younglings.bot.commands.ticket.TicketRules;
import com.younglings.bot.commands.ticket.TicketService;
import com.younglings.bot.internal.TicketAdminJson.BadRequest;
import com.younglings.bot.permission.DashboardAccess;
import com.younglings.bot.permission.DashboardAccess.Tier;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelDefinition;
import com.younglings.bot.ticket.TicketModels.Settings;
import com.younglings.bot.ticket.TicketModels.Status;
import com.younglings.bot.ticket.TicketModels.Ticket;
import com.younglings.bot.ticket.TicketRepository;
import com.younglings.bot.ticket.TicketRepository.Transcript;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The ticket dashboard's side of the internal API, under {@code /internal/admin/}. {@link InternalApiServer}
 * has already checked the shared secret and found the guild; this class additionally checks <em>who</em> is
 * asking. The website sends the logged-in user's Discord id in {@code X-Actor-Id}, and every request here —
 * not just a login check at the start — looks that person up in the guild and refuses unless they are Admin
 * tier or hold the Developer role (see {@link DashboardAccess}). A leaked secret alone therefore can't act as
 * a user the website never authenticated, and a demoted admin loses access on their next click.
 * <p>
 * Everything is read from and written to the database through {@link TicketRepository}, so nothing here holds
 * state of its own.
 */
@BService
public class TicketAdminApi {
    private static final Logger log = LoggerFactory.getLogger(TicketAdminApi.class);
    private static final String ACTOR_HEADER = "X-Actor-Id";
    private static final String PREFIX = "/internal/admin/";
    private static final int MAX_PAGE = 100;

    private final DashboardAccess access;
    private final TicketRepository repository;
    private final TicketService service;
    private final ClanAdminApi clanAdmin;
    private final CommunityAdminApi communityAdmin;
    /** Writes per admin: a few quick clicks are fine, a sustained flood is refused. */
    private final RateLimiter adminWrites = new RateLimiter(20, 1_000);
    private final AdminOpsApi ops;
    private final AdminToolsStore auditStore;
    private final HelpOnboarding onboarding;

    public TicketAdminApi(DashboardAccess access, TicketRepository repository, TicketService service, ClanAdminApi clanAdmin, CommunityAdminApi communityAdmin, AdminOpsApi ops, AdminToolsStore auditStore,
                          HelpOnboarding onboarding) {
        this.onboarding = onboarding;
        this.access = access;
        this.repository = repository;
        this.service = service;
        this.clanAdmin = clanAdmin;
        this.communityAdmin = communityAdmin;
        this.ops = ops;
        this.auditStore = auditStore;
    }

    /** A failure with the status and message the website should see. */
    static final class ApiError extends RuntimeException {
        final int status;
        final List<String> problems;

        ApiError(int status, String message) {
            this(status, message, List.of());
        }

        ApiError(int status, String message, List<String> problems) {
            super(message);
            this.status = status;
            this.problems = problems;
        }
    }

    public void handle(HttpExchange exchange, Guild guild) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String route = path.startsWith(PREFIX) ? path.substring(PREFIX.length()) : "";
            if (route.endsWith("/")) route = route.substring(0, route.length() - 1);
            String[] parts = route.isEmpty() ? new String[0] : route.split("/");
            String method = exchange.getRequestMethod().toUpperCase();

            Member actor = actorOf(exchange, guild);
            Tier tier = actor == null ? Tier.NONE : access.tierOf(guild, actor);

            // whoami is the one route that answers "no" with a 200: the website uses it to decide what to show.
            if (parts.length == 1 && parts[0].equals("whoami")) {
                requireMethod(method, "GET");
                InternalApiServer.sendJson(exchange, 200, whoami(actor, tier));
                return;
            }
            if (tier == Tier.NONE) {
                InternalApiServer.sendJson(exchange, 403, DataObject.empty().put("error", "You don't have access to the dashboard."));
                return;
            }

            DataObject result = route(exchange, guild, actor, method, parts);
            if (result == null) {
                InternalApiServer.sendJson(exchange, 404, DataObject.empty().put("error", "Not found"));
                return;
            }
            // Every change made from the dashboard is written to the audit log (never the request body, which may hold private text).
            if (!method.equals("GET")) auditStore.audit(guild.getIdLong(), actor.getId(), actor.getEffectiveName(), method, route, 200);
            InternalApiServer.sendJson(exchange, 200, result);
        } catch (ApiError e) {
            DataObject body = DataObject.empty().put("error", e.getMessage());
            if (!e.problems.isEmpty()) {
                DataArray problems = DataArray.empty();
                e.problems.forEach(problems::add);
                body.put("problems", problems);
            }
            InternalApiServer.sendJson(exchange, e.status, body);
        } catch (BadRequest e) {
            InternalApiServer.sendJson(exchange, 400, DataObject.empty().put("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Admin API request {} {} failed", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e);
            InternalApiServer.sendJson(exchange, 500, DataObject.empty().put("error", "Internal error"));
        }
    }

    // ---------- who is asking ----------

    private Member actorOf(HttpExchange exchange, Guild guild) {
        String raw = exchange.getRequestHeaders().getFirst(ACTOR_HEADER);
        if (raw == null || raw.isBlank()) return null;
        long id;
        try {
            id = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        Member cached = guild.getMemberById(id);
        if (cached != null) return cached;
        try {
            return guild.retrieveMemberById(id).complete();
        } catch (Exception e) {
            return null; // not in the server (or Discord couldn't say) — no access
        }
    }

    private static DataObject whoami(Member actor, Tier tier) {
        DataObject json = DataObject.empty().put("allowed", tier != Tier.NONE).put("tier", tier.name());
        if (actor != null) {
            json.put("id", actor.getId()).put("displayName", actor.getEffectiveName()).put("avatarUrl", actor.getEffectiveAvatarUrl());
        }
        return json;
    }

    private static void requireMethod(String actual, String expected) {
        if (!actual.equals(expected)) throw new ApiError(405, "Method not allowed");
    }

    // ---------- routing ----------

    /** {@code null} for a path that doesn't exist. */
    private DataObject route(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) throws IOException {
        if (parts.length == 1 && parts[0].equals("structure")) {
            requireMethod(method, "GET");
            return structure(guild);
        }
        if (!method.equals("GET") && !adminWrites.tryAcquire(actor.getId())) throw new ApiError(429, "You're doing that too fast — give it a moment.");

        DataObject opsResult = opsRoute(exchange, guild, actor, method, parts);
        if (opsResult != null) return opsResult;

        if (parts.length >= 1 && (parts[0].equals("polls") || parts[0].equals("signups"))) return communityRoute(exchange, guild, actor, method, parts);
        if (parts.length == 1 && parts[0].equals("selfroles")) {
            if (method.equals("GET")) return clanAdmin.selfRoles(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveSelfRoles(guild, actor, body(exchange));
        }
        if (parts.length >= 1 && parts[0].equals("promotions")) {
            if (parts.length == 1) {
                requireMethod(method, "GET");
                return clanAdmin.promotions(guild);
            }
            if (parts.length == 3 && parts[2].equals("done")) {
                requireMethod(method, "POST");
                return clanAdmin.markPromoted(guild, actor, parts[1]);
            }
            return null;
        }
        if (parts.length >= 1 && parts[0].equals("tracking")) {
            if (parts.length == 1) {
                requireMethod(method, "GET");
                return clanAdmin.tracking(guild);
            }
            requireMethod(method, "PUT");
            return clanAdmin.saveTracking(guild, actor, parts[1], body(exchange));
        }
        if (parts.length == 1 && parts[0].equals("post")) {
            requireMethod(method, "POST");
            return clanAdmin.post(guild, actor, body(exchange));
        }
        if (parts.length == 1 && parts[0].equals("community")) {
            if (method.equals("GET")) return clanAdmin.community(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveCommunity(guild, actor, body(exchange));
        }
        if (parts.length == 1 && parts[0].equals("news")) {
            if (method.equals("GET")) return clanAdmin.newsChannels(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveNewsChannels(guild, actor, body(exchange));
        }
        if (parts.length == 2 && parts[0].equals("clan") && parts[1].equals("website")) {
            if (method.equals("GET")) return clanAdmin.clanWebsite(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveClanWebsite(guild, actor, body(exchange));
        }
        if (parts.length == 2 && parts[0].equals("site") && parts[1].equals("options")) {
            if (method.equals("GET")) return clanAdmin.siteOptions(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveSiteOptions(guild, actor, body(exchange));
        }
        if (parts.length == 2 && parts[0].equals("clan") && parts[1].equals("points")) {
            if (method.equals("GET")) return clanAdmin.clanPoints(guild);
            requireMethod(method, "PUT");
            return clanAdmin.saveClanPoints(guild, actor, body(exchange));
        }
        if (parts.length >= 2 && parts[0].equals("help")) return helpRoute(exchange, guild, actor, method, parts);
        if (parts.length < 2 || !parts[0].equals("ticket")) return null;

        switch (parts[1]) {
            case "settings" -> {
                if (parts.length != 2) return null;
                if (method.equals("GET")) return TicketAdminJson.settingsJson(repository.getSettings(guild.getIdLong()));
                requireMethod(method, "PUT");
                return saveSettings(exchange, guild, actor);
            }
            case "panels" -> {
                return routePanels(exchange, guild, actor, method, parts);
            }
            case "defaults" -> {
                if (parts.length != 2) return null;
                if (method.equals("GET")) return panelDefaults(guild);
                requireMethod(method, "PUT");
                return savePanelDefaults(exchange, guild, actor);
            }
            case "stats" -> {
                if (parts.length != 2) return null;
                requireMethod(method, "GET");
                return stats(guild);
            }
            case "tickets" -> {
                requireMethod(method, "GET");
                if (parts.length == 2) return listTickets(exchange, guild);
                if (parts.length == 3) return ticketDetail(guild, parts[2]);
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    /** Roster view, attention list, health, audit log, notes and scheduled posts; {@code null} when the path isn't one of those. */
    private DataObject opsRoute(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) {
        if (parts.length == 0) return null;
        switch (parts[0]) {
            case "members" -> {
                if (parts.length != 1) return null;
                requireMethod(method, "GET");
                return ops.members(guild);
            }
            case "attention" -> {
                requireMethod(method, "GET");
                return ops.attention(guild);
            }
            case "health" -> {
                requireMethod(method, "GET");
                return ops.health(guild);
            }
            case "audit" -> {
                requireMethod(method, "GET");
                return ops.audit(guild, queryParam(exchange, "limit"), queryParam(exchange, "actor"), queryParam(exchange, "q"));
            }
            case "notes" -> {
                if (parts.length == 1 && method.equals("GET")) return ops.notes(guild, queryParam(exchange, "rsn"));
                if (parts.length == 1) {
                    requireMethod(method, "POST");
                    return ops.addNote(guild, actor, body(exchange));
                }
                if (parts.length == 2) {
                    requireMethod(method, "DELETE");
                    return ops.deleteNote(guild, idOf(parts[1]), queryParam(exchange, "rsn"));
                }
                return null;
            }
            case "scheduled" -> {
                if (parts.length == 1 && method.equals("GET")) return ops.scheduled(guild);
                if (parts.length == 1) {
                    requireMethod(method, "POST");
                    return ops.schedulePost(guild, actor, body(exchange));
                }
                if (parts.length == 2) {
                    requireMethod(method, "DELETE");
                    return ops.cancelScheduled(guild, idOf(parts[1]));
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return null;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    /** Polls and signups: {@code POST polls}, {@code POST polls/{id}/end}, {@code GET|POST signups}, {@code POST signups/{id}/{action}}. */
    private DataObject communityRoute(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) {
        if (parts[0].equals("polls")) {
            if (parts.length == 1) {
                requireMethod(method, "POST");
                return communityAdmin.createPoll(guild, actor, body(exchange));
            }
            if (parts.length == 3 && parts[2].equals("end")) {
                requireMethod(method, "POST");
                return communityAdmin.endPoll(guild, actor, idOf(parts[1]));
            }
            return null;
        }

        if (parts.length == 1) {
            if (method.equals("GET")) return communityAdmin.signupList(guild);
            requireMethod(method, "POST");
            return communityAdmin.createSignup(guild, actor, body(exchange));
        }
        if (parts.length == 3) {
            requireMethod(method, "POST");
            DataObject body;
            try (var in = exchange.getRequestBody()) {
                byte[] bytes = in.readAllBytes();
                body = bytes.length == 0 ? DataObject.empty() : DataObject.fromJson(bytes);
            } catch (Exception e) {
                throw new ApiError(400, "The request body isn't valid JSON.");
            }
            return communityAdmin.signupAction(guild, actor, idOf(parts[1]), parts[2], body);
        }
        return null;
    }

    private DataObject routePanels(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) throws IOException {
        if (parts.length == 2) {
            if (method.equals("GET")) return listPanels(guild);
            requireMethod(method, "POST");
            return savePanel(exchange, guild, actor, 0);
        }

        long panelId = idOf(parts[2]);
        if (parts.length == 3) {
            return switch (method) {
                case "GET" -> panelDetail(guild, panelId);
                case "PUT" -> savePanel(exchange, guild, actor, panelId);
                case "DELETE" -> deletePanel(guild, actor, panelId);
                default -> throw new ApiError(405, "Method not allowed");
            };
        }
        if (parts.length == 4 && parts[3].equals("post")) {
            requireMethod(method, "POST");
            return postPanel(exchange, guild, actor, panelId);
        }
        return null;
    }

    private static long idOf(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new ApiError(400, "That isn't a valid id.");
        }
    }

    private static DataObject body(HttpExchange exchange) {
        try (var in = exchange.getRequestBody()) {
            return DataObject.fromJson(in);
        } catch (Exception e) {
            throw new ApiError(400, "The request body isn't valid JSON.");
        }
    }

    // ---------- the server's roles and channels, for the editor's pickers ----------

    private DataObject structure(Guild guild) {
        DataArray roles = DataArray.empty();
        for (Role role : guild.getRoles()) {
            if (role.isPublicRole()) continue;
            roles.add(DataObject.empty()
                    .put("id", role.getId())
                    .put("name", role.getName())
                    .put("color", role.getColorRaw() & 0xFFFFFF)
                    .put("managed", role.isManaged())
                    .put("position", role.getPosition()));
        }

        DataArray categories = DataArray.empty();
        for (Category category : guild.getCategories()) {
            categories.add(DataObject.empty().put("id", category.getId()).put("name", category.getName()));
        }

        DataArray channels = DataArray.empty();
        guild.getTextChannels().forEach(channel -> channels.add(DataObject.empty()
                .put("id", channel.getId())
                .put("name", channel.getName())
                .put("category", channel.getParentCategory() == null ? null : channel.getParentCategory().getName())
                .put("canPost", channel.canTalk())));

        return DataObject.empty().put("roles", roles).put("categories", categories).put("channels", channels);
    }

    // ---------- settings ----------

    private DataObject saveSettings(HttpExchange exchange, Guild guild, Member actor) {
        Settings current = repository.getSettings(guild.getIdLong());
        Settings updated = TicketAdminJson.readSettings(current, body(exchange));

        List<String> problems = new ArrayList<>();
        if (updated.logChannelId() != null && guild.getTextChannelById(updated.logChannelId()) == null) problems.add("The log channel isn't a text channel in this server.");
        if (updated.closeDelaySeconds() < 0 || updated.closeDelaySeconds() > 300) problems.add("The close delay must be between 0 and 300 seconds.");
        if (updated.transcriptRetentionDays() != null && (updated.transcriptRetentionDays() < 1 || updated.transcriptRetentionDays() > 3650)) problems.add("Transcript retention must be between 1 and 3650 days.");
        if (!problems.isEmpty()) throw new ApiError(400, "Those settings can't be saved.", problems);

        repository.saveSettings(updated);
        log.info("Dashboard: {} updated ticket settings", actor.getId());
        return TicketAdminJson.settingsJson(repository.getSettings(guild.getIdLong()));
    }

    // ---------- PvM Help ----------

    private DataObject helpRoute(HttpExchange exchange, Guild guild, Member actor, String method, String[] parts) {
        if (parts.length == 2 && parts[1].equals("settings")) {
            if (method.equals("GET")) return TicketAdminJson.helpSettingsJson(repository.getHelpSettings(guild.getIdLong()));
            requireMethod(method, "PUT");
            return saveHelpSettings(exchange, guild, actor);
        }
        if (parts.length == 3 && parts[1].equals("guidelines") && parts[2].equals("post")) {
            requireMethod(method, "POST");
            return postGuidelines(exchange, guild, actor);
        }
        return null;
    }

    private DataObject saveHelpSettings(HttpExchange exchange, Guild guild, Member actor) {
        HelpSettings updated = TicketAdminJson.readHelpSettings(repository.getHelpSettings(guild.getIdLong()), body(exchange));

        List<String> problems = new ArrayList<>(HelpRules.validate(updated));
        if (updated.helperRoleId() != null && guild.getRoleById(updated.helperRoleId()) == null) problems.add("The PVM Helper role no longer exists in this server.");
        if (updated.helperPlusRoleId() != null && guild.getRoleById(updated.helperPlusRoleId()) == null) problems.add("The PVM Helper+ role no longer exists in this server.");
        if (!problems.isEmpty()) throw new ApiError(400, "Those settings can't be saved.", problems);

        repository.saveHelpSettings(updated);
        onboarding.refreshPosted(guild); // new guidelines show up on the message members read
        log.info("Dashboard: {} updated the PvM Help settings", actor.getId());
        return TicketAdminJson.helpSettingsJson(repository.getHelpSettings(guild.getIdLong()));
    }

    private DataObject postGuidelines(HttpExchange exchange, Guild guild, Member actor) {
        Long channelId = TicketAdminJson.idOrNull(body(exchange), "channelId");
        if (channelId == null) throw new ApiError(400, "Choose a channel to post in.");
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new ApiError(400, "That isn't a text channel in this server.");
        if (!channel.canTalk()) throw new ApiError(400, "JonnyBot can't post in #" + channel.getName() + " — it needs permission to view and send messages there.");
        if (repository.getHelpSettings(guild.getIdLong()).helperRoleId() == null) throw new ApiError(400, "Choose the PVM Helper role first — the button on that message hands it out.");

        try {
            onboarding.post(guild, channel).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Posting the helper guidelines to {} failed", channelId, e);
            throw new ApiError(502, "Discord wouldn't let the guidelines be posted there.");
        }
        log.info("Dashboard: {} posted the helper guidelines in {}", actor.getId(), channelId);
        return TicketAdminJson.helpSettingsJson(repository.getHelpSettings(guild.getIdLong()));
    }

    // ---------- what a new panel starts with ----------

    /** The saved starting values for new panels, or the built-in ones if none were saved yet. */
    private DataObject panelDefaults(Guild guild) {
        String stored = repository.getPanelDefaults(guild.getIdLong());
        if (stored == null) return TicketAdminJson.builtinDefaults();
        try {
            DataObject json = DataObject.fromJson(stored);
            // Anything saved before a setting existed reads as that setting's built-in value.
            DataObject merged = TicketAdminJson.builtinDefaults();
            for (String key : json.keys()) merged.put(key, json.get(key));
            return merged;
        } catch (RuntimeException e) {
            log.warn("The saved default panel settings for guild {} couldn't be read; using the built-in ones", guild.getId(), e);
            return TicketAdminJson.builtinDefaults();
        }
    }

    private DataObject savePanelDefaults(HttpExchange exchange, Guild guild, Member actor) {
        // Read it the way a panel is read, so the same limits and checks apply; the name and title are stand-ins that never get stored.
        DataObject body = body(exchange).put("name", "Defaults").put("title", "Defaults");
        PanelDefinition definition = TicketAdminJson.readPanel(guild.getIdLong(), 0, body);

        List<String> problems = new ArrayList<>(TicketRules.validatePanel(definition.panel(), List.of()));
        problems.addAll(guildProblems(guild, definition));
        if (!problems.isEmpty()) throw new ApiError(400, "Those defaults can't be saved yet.", problems);

        repository.savePanelDefaults(guild.getIdLong(), TicketAdminJson.defaultsJson(definition).toString());
        log.info("Dashboard: {} updated the default settings for new ticket panels", actor.getId());
        return panelDefaults(guild);
    }

    // ---------- panels ----------

    private DataObject listPanels(Guild guild) {
        DataArray panels = DataArray.empty();
        for (Panel panel : repository.getPanels(guild.getIdLong())) {
            panels.add(TicketAdminJson.panelJson(panel)
                    .put("fieldCount", repository.getFields(panel.id()).size())
                    .put("openTickets", repository.listTickets(guild.getIdLong(), Status.OPEN, panel.id(), MAX_PAGE, 0).size()));
        }
        return DataObject.empty().put("panels", panels);
    }

    private PanelDefinition ownedDefinition(Guild guild, long panelId) {
        Panel panel = repository.getPanel(panelId);
        if (panel == null || panel.guildId() != guild.getIdLong()) throw new ApiError(404, "That panel doesn't exist.");
        return repository.getDefinition(panelId);
    }

    private DataObject panelDetail(Guild guild, long panelId) {
        return TicketAdminJson.definitionJson(ownedDefinition(guild, panelId));
    }

    private DataObject savePanel(HttpExchange exchange, Guild guild, Member actor, long panelId) {
        if (panelId != 0) ownedDefinition(guild, panelId); // 404 for another server's or a missing panel
        PanelDefinition definition = TicketAdminJson.readPanel(guild.getIdLong(), panelId, body(exchange));

        List<String> problems = new ArrayList<>(TicketRules.validatePanel(definition.panel(), definition.fields()));
        problems.addAll(HelpRules.validatePanelHelp(definition.panel(), definition.fields()));
        problems.addAll(guildProblems(guild, definition));
        if (!problems.isEmpty()) throw new ApiError(400, "That panel can't be saved yet.", problems);

        long savedId;
        try {
            savedId = repository.saveDefinition(definition);
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "That panel can't be saved yet.", List.of(e.getMessage()));
        }
        log.info("Dashboard: {} {} ticket panel {} (\"{}\")", actor.getId(), panelId == 0 ? "created" : "updated", savedId, definition.panel().name());

        refreshPostedMessage(guild, savedId);
        return TicketAdminJson.definitionJson(repository.getDefinition(savedId));
    }

    /** Roles and the category must exist in this server — the dashboard's pickers come from it, but a stale page may not match. */
    private static List<String> guildProblems(Guild guild, PanelDefinition definition) {
        List<String> problems = new ArrayList<>();
        Panel panel = definition.panel();

        if (panel.categoryId() != null && guild.getCategoryById(panel.categoryId()) == null) problems.add("The chosen category no longer exists.");

        Set<Long> roleIds = new HashSet<>();
        roleIds.addAll(definition.roles().helperRoleIds());
        roleIds.addAll(definition.roles().staffRoleIds());
        roleIds.addAll(definition.roles().closeRoleIds());
        if (panel.defaultPingRoleId() != null) roleIds.add(panel.defaultPingRoleId());
        if (panel.defaultEscalateRoleId() != null) roleIds.add(panel.defaultEscalateRoleId());
        for (Field field : definition.fields()) {
            for (Option option : field.options()) {
                if (option.pingRoleId() != null) roleIds.add(option.pingRoleId());
                if (option.escalateRoleId() != null) roleIds.add(option.escalateRoleId());
            }
        }
        for (long roleId : roleIds) {
            if (guild.getRoleById(roleId) == null) {
                problems.add("A chosen role (" + roleId + ") no longer exists in this server.");
                break;
            }
        }
        return problems;
    }

    /** If the panel is already posted, bring that message up to date with the saved wording. Best effort — the save itself has succeeded. */
    private void refreshPostedMessage(Guild guild, long panelId) {
        Panel panel = repository.getPanel(panelId);
        if (panel == null || panel.postedChannelId() == null) return;
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, panel.postedChannelId());
        if (channel == null) return;
        service.postPanel(panel, channel).exceptionally(error -> {
            log.warn("Could not refresh the posted message for ticket panel {}", panelId, error);
            return null;
        });
    }

    private DataObject deletePanel(Guild guild, Member actor, long panelId) {
        Panel panel = ownedDefinition(guild, panelId).panel();
        if (!repository.listTickets(guild.getIdLong(), Status.OPEN, panelId, 1, 0).isEmpty()) {
            throw new ApiError(409, "This panel still has open tickets — close them first.");
        }
        repository.deletePanel(guild.getIdLong(), panelId);
        log.info("Dashboard: {} deleted ticket panel {} (\"{}\")", actor.getId(), panelId, panel.name());

        // Take the now-dead button down too (best effort).
        if (panel.postedChannelId() != null && panel.postedMessageId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, panel.postedChannelId());
            if (channel != null) channel.deleteMessageById(panel.postedMessageId()).queue(null, error -> {});
        }
        return DataObject.empty().put("deleted", true);
    }

    private DataObject postPanel(HttpExchange exchange, Guild guild, Member actor, long panelId) {
        Panel panel = ownedDefinition(guild, panelId).panel();
        Long channelId = TicketAdminJson.idOrNull(body(exchange), "channelId");
        if (channelId == null) throw new ApiError(400, "Choose a channel to post in.");

        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, channelId);
        if (channel == null) throw new ApiError(400, "That isn't a text channel in this server.");
        if (!channel.canTalk()) throw new ApiError(400, "JonnyBot can't post in #" + channel.getName() + " — it needs permission to view and send messages there.");

        try {
            service.postPanel(panel, channel).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Posting ticket panel {} to {} failed", panelId, channelId, e);
            throw new ApiError(502, "Discord wouldn't let the panel be posted there.");
        }
        log.info("Dashboard: {} posted ticket panel {} in {}", actor.getId(), panelId, channelId);
        return TicketAdminJson.panelJson(repository.getPanel(panelId));
    }

    // ---------- tickets ----------

    private DataObject stats(Guild guild) {
        var stats = repository.stats(guild.getIdLong());

        DataArray byPanel = DataArray.empty();
        stats.byPanel().forEach(p -> byPanel.add(DataObject.empty().put("panel", p.panel()).put("total", p.total()).put("open", p.open())));
        DataArray byWeek = DataArray.empty();
        stats.byWeek().forEach(w -> byWeek.add(DataObject.empty().put("weekStart", w.weekStart().toString()).put("opened", w.opened())));
        DataArray helpers = DataArray.empty();
        stats.topHelpers().forEach(h -> helpers.add(DataObject.empty().put("id", Long.toString(h.userId())).put("name", nameOf(guild, h.userId())).put("tickets", h.tickets())));

        return DataObject.empty()
                .put("open", stats.open()).put("closed", stats.closed()).put("escalated", stats.escalated()).put("flagged", stats.flagged())
                .put("avgHoursToClose", stats.avgHoursToClose()).put("avgMinutesToFirstHelper", stats.avgMinutesToFirstHelper())
                .put("byPanel", byPanel).put("byWeek", byWeek).put("topHelpers", helpers);
    }

    private DataObject listTickets(HttpExchange exchange, Guild guild) {
        String statusRaw = query(exchange, "status");
        Status status = null;
        if (statusRaw != null && !statusRaw.isBlank() && !statusRaw.equalsIgnoreCase("ALL")) {
            try {
                status = Status.valueOf(statusRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ApiError(400, "Status must be OPEN, CLOSED or ALL.");
            }
        }
        String panelRaw = query(exchange, "panel");
        Long panelId = panelRaw == null || panelRaw.isBlank() ? null : idOf(panelRaw);
        int limit = Math.max(1, Math.min(MAX_PAGE, numberOr(query(exchange, "limit"), 25)));
        int offset = Math.max(0, numberOr(query(exchange, "offset"), 0));

        // One extra row tells us whether there's another page without a separate count query.
        List<Ticket> rows = repository.listTickets(guild.getIdLong(), status, panelId, limit + 1, offset);
        boolean hasMore = rows.size() > limit;

        DataArray tickets = DataArray.empty();
        for (Ticket ticket : rows.subList(0, Math.min(limit, rows.size()))) {
            tickets.add(TicketAdminJson.ticketJson(ticket, panelNameOf(ticket), cachedNameOf(guild, ticket.requesterId())));
        }
        return DataObject.empty().put("tickets", tickets).put("hasMore", hasMore);
    }

    private DataObject ticketDetail(Guild guild, String rawId) {
        Ticket ticket = repository.getTicket(idOf(rawId));
        if (ticket == null || ticket.guildId() != guild.getIdLong()) throw new ApiError(404, "That ticket doesn't exist.");

        DataArray helpers = DataArray.empty();
        for (long helperId : repository.getHelpers(ticket.id())) {
            helpers.add(DataObject.empty().put("id", Long.toString(helperId)).put("name", nameOf(guild, helperId)));
        }

        Transcript transcript = repository.getTranscript(ticket.id());
        DataObject json = TicketAdminJson.ticketJson(ticket, panelNameOf(ticket), nameOf(guild, ticket.requesterId()))
                .put("helpers", helpers)
                .put("closedByName", ticket.closedBy() == null ? null : nameOf(guild, ticket.closedBy()))
                .put("transcript", transcript == null ? null : transcript.content())
                .put("transcriptMessageCount", transcript == null ? null : transcript.messageCount());
        return json;
    }

    private String panelNameOf(Ticket ticket) {
        if (ticket.panelId() == null) return null;
        Panel panel = repository.getPanel(ticket.panelId());
        return panel == null ? null : panel.name();
    }

    /** Display name from the member cache only — fine for a list, no network call per row. */
    private static String cachedNameOf(Guild guild, long userId) {
        Member member = guild.getMemberById(userId);
        if (member != null) return member.getEffectiveName();
        var user = guild.getJDA().getUserById(userId);
        return user == null ? null : user.getName();
    }

    /** Display name, asking Discord if the cache doesn't know — for a single ticket's page. */
    private static String nameOf(Guild guild, long userId) {
        String cached = cachedNameOf(guild, userId);
        if (cached != null) return cached;
        try {
            return guild.getJDA().retrieveUserById(userId).complete().getName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String query(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static int numberOr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
