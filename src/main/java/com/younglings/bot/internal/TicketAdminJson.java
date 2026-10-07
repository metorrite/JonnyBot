package com.younglings.bot.internal;

import com.younglings.bot.ticket.TicketModels.Answer;
import com.younglings.bot.ticket.TicketModels.Field;
import com.younglings.bot.ticket.TicketModels.FieldKind;
import com.younglings.bot.ticket.TicketModels.FieldPurpose;
import com.younglings.bot.ticket.TicketModels.HelpKind;
import com.younglings.bot.ticket.TicketModels.HelpSettings;
import com.younglings.bot.ticket.TicketModels.Option;
import com.younglings.bot.ticket.TicketModels.Panel;
import com.younglings.bot.ticket.TicketModels.PanelDefinition;
import com.younglings.bot.ticket.TicketModels.PanelRoles;
import com.younglings.bot.ticket.TicketModels.Settings;
import com.younglings.bot.ticket.TicketModels.Ticket;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Converts the ticket system's records to and from the JSON the website's dashboard sends and receives.
 * <p>
 * Every Discord id travels as a <em>string</em>: ids are 64-bit and a JavaScript number only holds 53 bits
 * exactly, so a numeric id would be silently corrupted on its way through the website. Anything the client
 * didn't send, or sent as {@code null}, reads as "not set".
 */
final class TicketAdminJson {
    private TicketAdminJson() {}

    /** A request body the dashboard sent that can't be understood — reported back as a 400. */
    static final class BadRequest extends RuntimeException {
        BadRequest(String message) {
            super(message);
        }
    }

    // ---------- reading ----------

    static Long idOrNull(DataObject json, String key) {
        if (json.isNull(key)) return null;
        String raw = json.getString(key).trim();
        if (raw.isEmpty()) return null;
        try {
            long id = Long.parseLong(raw);
            if (id <= 0) throw new BadRequest("\"" + key + "\" is not a valid id.");
            return id;
        } catch (NumberFormatException e) {
            throw new BadRequest("\"" + key + "\" is not a valid id.");
        }
    }

    static Integer intOrNull(DataObject json, String key) {
        if (json.isNull(key)) return null;
        Object raw = json.toMap().get(key);
        if (raw instanceof Number number) return number.intValue();
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) return null;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new BadRequest("\"" + key + "\" must be a whole number.");
        }
    }

    private static String text(DataObject json, String key, String fallback) {
        String value = json.getString(key, fallback);
        return value == null ? null : value.strip();
    }

    private static Set<Long> idSet(DataObject json, String key) {
        Set<Long> ids = new LinkedHashSet<>();
        if (json.isNull(key)) return ids;
        DataArray array = json.getArray(key);
        for (int i = 0; i < array.length(); i++) {
            try {
                ids.add(Long.parseLong(array.getString(i).trim()));
            } catch (NumberFormatException e) {
                throw new BadRequest("\"" + key + "\" holds something that isn't an id.");
            }
        }
        return ids;
    }

    /** {@code panelId} is 0 for a panel being created. Posting details are never read from the client — the bot owns those. */
    static PanelDefinition readPanel(long guildId, long panelId, DataObject json) {
        Panel panel = new Panel(panelId, guildId,
                text(json, "name", ""),
                text(json, "title", ""),
                json.getString("description", "").strip(),
                text(json, "buttonLabel", ""),
                idOrNull(json, "categoryId"),
                text(json, "channelNameTemplate", ""),
                json.getString("welcomeText", "").strip(),
                json.getBoolean("enabled", true),
                intOr(json, "perUserLimit", 1),
                idOrNull(json, "defaultPingRoleId"),
                intOrNull(json, "helperCap"),
                intOrNull(json, "escalationHours"),
                idOrNull(json, "defaultEscalateRoleId"),
                null, null,
                json.getString("openingMessage", Panel.DEFAULT_OPENING).strip(),
                json.getBoolean("closeByRequester", true),
                json.getBoolean("closeByHelpers", true),
                enumOr(HelpKind.class, json, "helpKind", HelpKind.NONE, "A panel has an unknown help type"));

        List<Field> fields = new ArrayList<>();
        if (!json.isNull("fields")) {
            DataArray array = json.getArray("fields");
            for (int i = 0; i < array.length(); i++) {
                fields.add(readField(panelId, i, array.getObject(i)));
            }
        }
        return new PanelDefinition(panel, fields, new PanelRoles(idSet(json, "helperRoleIds"), idSet(json, "staffRoleIds"), idSet(json, "closeRoleIds")));
    }

    // ---------- what a new panel starts with ----------

    /**
     * The starting values for a new panel: the settings an admin tends to repeat on every panel (where tickets go, who staffs them,
     * who may close them, the limits, the standard wording), never the panel's own name, title, description or questions.
     */
    static DataObject defaultsJson(PanelDefinition definition) {
        Panel p = definition.panel();
        return DataObject.empty()
                .put("buttonLabel", p.buttonLabel())
                .put("categoryId", idString(p.categoryId()))
                .put("channelNameTemplate", p.channelNameTemplate())
                .put("welcomeText", p.welcomeText())
                .put("openingMessage", p.openingMessage())
                .put("perUserLimit", p.perUserLimit())
                .put("defaultPingRoleId", idString(p.defaultPingRoleId()))
                .put("helperCap", p.helperCap())
                .put("escalationHours", p.escalationHours())
                .put("defaultEscalateRoleId", idString(p.defaultEscalateRoleId()))
                .put("closeByRequester", p.closeByRequester())
                .put("closeByHelpers", p.closeByHelpers())
                .put("helperRoleIds", idArray(definition.roles().helperRoleIds()))
                .put("staffRoleIds", idArray(definition.roles().staffRoleIds()))
                .put("closeRoleIds", idArray(definition.roles().closeRoleIds()));
    }

    /** What a new panel starts with before anyone has saved their own defaults. */
    static DataObject builtinDefaults() {
        return DataObject.empty()
                .put("buttonLabel", "Open a Ticket")
                .put("categoryId", null)
                .put("channelNameTemplate", "ticket-{number}")
                .put("welcomeText", "")
                .put("openingMessage", Panel.DEFAULT_OPENING)
                .put("perUserLimit", 1)
                .put("defaultPingRoleId", null)
                .put("helperCap", null)
                .put("escalationHours", null)
                .put("defaultEscalateRoleId", null)
                .put("closeByRequester", true)
                .put("closeByHelpers", true)
                .put("helperRoleIds", DataArray.empty())
                .put("staffRoleIds", DataArray.empty())
                .put("closeRoleIds", DataArray.empty());
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, DataObject json, String key, E fallback, String complaint) {
        if (json.isNull(key)) return fallback;
        String raw = json.getString(key, "").strip();
        if (raw.isEmpty()) return fallback;
        try {
            return Enum.valueOf(type, raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequest(complaint + " \"" + raw + "\".");
        }
    }

    private static int intOr(DataObject json, String key, int fallback) {
        Integer value = intOrNull(json, key);
        return value == null ? fallback : value;
    }

    private static Field readField(long panelId, int position, DataObject json) {
        FieldKind kind;
        try {
            kind = FieldKind.valueOf(json.getString("kind", "SHORT").toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequest("A question has an unknown type \"" + json.getString("kind", "") + "\".");
        }

        List<Option> options = new ArrayList<>();
        if (kind == FieldKind.SELECT && !json.isNull("options")) {
            DataArray array = json.getArray("options");
            for (int i = 0; i < array.length(); i++) {
                DataObject option = array.getObject(i);
                options.add(new Option(0, 0, i, text(option, "label", ""), idOrNull(option, "pingRoleId"), idOrNull(option, "escalateRoleId")));
            }
        }

        String placeholder = text(json, "placeholder", null);
        return new Field(0, panelId, position, text(json, "label", ""), kind, json.getBoolean("required", true),
                placeholder == null || placeholder.isEmpty() ? null : placeholder, intOrNull(json, "maxLength"), options,
                enumOr(FieldPurpose.class, json, "purpose", FieldPurpose.NONE, "A question has an unknown role in the help rules"));
    }

    static Settings readSettings(Settings current, DataObject json) {
        return new Settings(current.guildId(), idOrNull(json, "logChannelId"), current.nextNumber(),
                json.getBoolean("transcriptDm", current.transcriptDm()),
                intOr(json, "closeDelaySeconds", current.closeDelaySeconds()),
                intOrNull(json, "transcriptRetentionDays"));
    }

    // ---------- writing ----------

    private static String idString(Long id) {
        return id == null ? null : Long.toString(id);
    }

    private static DataArray idArray(Set<Long> ids) {
        DataArray array = DataArray.empty();
        ids.stream().sorted().forEach(id -> array.add(Long.toString(id)));
        return array;
    }

    static DataObject settingsJson(Settings settings) {
        return DataObject.empty()
                .put("logChannelId", idString(settings.logChannelId()))
                .put("nextNumber", settings.nextNumber())
                .put("transcriptDm", settings.transcriptDm())
                .put("closeDelaySeconds", settings.closeDelaySeconds())
                .put("transcriptRetentionDays", settings.transcriptRetentionDays());
    }

    // ---------- PvM Help settings ----------

    static DataObject helpSettingsJson(HelpSettings settings) {
        return DataObject.empty()
                .put("helperRoleId", idString(settings.helperRoleId()))
                .put("helperPlusRoleId", idString(settings.helperPlusRoleId()))
                .put("guidelines", settings.guidelinesOrDefault())
                .put("guidelinesAreDefault", settings.guidelines() == null || settings.guidelines().isBlank())
                .put("defaultGuidelines", HelpSettings.DEFAULT_GUIDELINES)
                .put("memberPingOnOpen", settings.memberPingOnOpen())
                .put("memberEscalationHours", settings.memberEscalationHours())
                .put("guestPingsEnabled", settings.guestPingsEnabled())
                .put("guestPingOnOpen", settings.guestPingOnOpen())
                .put("guestEscalationHours", settings.guestEscalationHours())
                .put("guestHighTierNeedsAttempts", settings.guestHighTierNeedsAttempts())
                .put("highTierLabels", settings.highTierLabels())
                .put("postedChannelId", idString(settings.postedChannelId()));
    }

    /**
     * Applies what the dashboard sent over the saved settings; anything it left out keeps its value. Guidelines equal to the built-in
     * draft (or blank) are stored as "not edited" so the draft can improve later without anyone's saved copy hiding it.
     */
    static HelpSettings readHelpSettings(HelpSettings current, DataObject json) {
        String sent = json.isNull("guidelines") ? null : json.getString("guidelines", "").strip();
        String guidelines = sent == null ? current.guidelines() : (sent.isEmpty() || sent.equals(HelpSettings.DEFAULT_GUIDELINES) ? null : sent);
        return new HelpSettings(current.guildId(),
                json.hasKey("helperRoleId") ? idOrNull(json, "helperRoleId") : current.helperRoleId(),
                json.hasKey("helperPlusRoleId") ? idOrNull(json, "helperPlusRoleId") : current.helperPlusRoleId(),
                guidelines,
                json.getBoolean("memberPingOnOpen", current.memberPingOnOpen()),
                json.hasKey("memberEscalationHours") ? intOrNull(json, "memberEscalationHours") : current.memberEscalationHours(),
                json.getBoolean("guestPingsEnabled", current.guestPingsEnabled()),
                json.getBoolean("guestPingOnOpen", current.guestPingOnOpen()),
                json.hasKey("guestEscalationHours") ? intOrNull(json, "guestEscalationHours") : current.guestEscalationHours(),
                json.getBoolean("guestHighTierNeedsAttempts", current.guestHighTierNeedsAttempts()),
                json.hasKey("highTierLabels") ? json.getString("highTierLabels", "").strip() : current.highTierLabels(),
                current.postedChannelId(), current.postedMessageId());
    }

    /** The panel's own columns plus where it's posted — what the list view shows. */
    static DataObject panelJson(Panel panel) {
        return DataObject.empty()
                .put("id", Long.toString(panel.id()))
                .put("name", panel.name())
                .put("title", panel.title())
                .put("description", panel.description())
                .put("buttonLabel", panel.buttonLabel())
                .put("categoryId", idString(panel.categoryId()))
                .put("channelNameTemplate", panel.channelNameTemplate())
                .put("welcomeText", panel.welcomeText())
                .put("openingMessage", panel.openingMessage())
                .put("closeByRequester", panel.closeByRequester())
                .put("closeByHelpers", panel.closeByHelpers())
                .put("helpKind", panel.helpKind().name())
                .put("enabled", panel.enabled())
                .put("perUserLimit", panel.perUserLimit())
                .put("defaultPingRoleId", idString(panel.defaultPingRoleId()))
                .put("helperCap", panel.helperCap())
                .put("escalationHours", panel.escalationHours())
                .put("defaultEscalateRoleId", idString(panel.defaultEscalateRoleId()))
                .put("postedChannelId", idString(panel.postedChannelId()))
                .put("postedMessageId", idString(panel.postedMessageId()));
    }

    /** The full editable definition: the panel, its roles, and its questions with their choices. */
    static DataObject definitionJson(PanelDefinition definition) {
        DataObject json = panelJson(definition.panel())
                .put("helperRoleIds", idArray(definition.roles().helperRoleIds()))
                .put("staffRoleIds", idArray(definition.roles().staffRoleIds()))
                .put("closeRoleIds", idArray(definition.roles().closeRoleIds()));

        DataArray fields = DataArray.empty();
        for (Field field : definition.fields()) {
            DataArray options = DataArray.empty();
            for (Option option : field.options()) {
                options.add(DataObject.empty()
                        .put("label", option.label())
                        .put("pingRoleId", idString(option.pingRoleId()))
                        .put("escalateRoleId", idString(option.escalateRoleId())));
            }
            fields.add(DataObject.empty()
                    .put("label", field.label())
                    .put("kind", field.kind().name())
                    .put("required", field.required())
                    .put("placeholder", field.placeholder())
                    .put("maxLength", field.maxLength())
                    .put("purpose", field.purpose().name())
                    .put("options", options));
        }
        return json.put("fields", fields);
    }

    private static String time(OffsetDateTime time) {
        return time == null ? null : time.toString();
    }

    /** {@code panelName} may be {@code null} (the panel was deleted since); names are best-effort display text. */
    static DataObject ticketJson(Ticket ticket, String panelName, String requesterName) {
        DataArray answers = DataArray.empty();
        for (Answer answer : ticket.answers()) {
            answers.add(DataObject.empty().put("label", answer.label()).put("answer", answer.answer()));
        }
        return DataObject.empty()
                .put("id", Long.toString(ticket.id()))
                .put("number", ticket.number())
                .put("panelId", idString(ticket.panelId()))
                .put("panelName", panelName)
                .put("channelId", idString(ticket.channelId()))
                .put("requesterId", Long.toString(ticket.requesterId()))
                .put("requesterName", requesterName)
                .put("status", ticket.status().name())
                .put("routingLabel", ticket.routingLabel())
                .put("answers", answers)
                .put("createdAt", time(ticket.createdAt()))
                .put("escalatedAt", time(ticket.escalatedAt()))
                .put("closedAt", time(ticket.closedAt()))
                .put("closedBy", idString(ticket.closedBy()))
                .put("closeReason", ticket.closeReason())
                .put("channelDeleted", ticket.channelDeleted());
    }
}
