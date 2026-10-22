package com.younglings.bot.internal;

import com.younglings.bot.i18n.Messages;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * The dashboard's Language page: which language JonnyBot's messages use in this server, and which languages exist. Today only English does;
 * a language pack (see docs/TRANSLATION.md) shows up here by itself. Reached only through {@link TicketAdminApi}, which has already checked
 * the asker may use the dashboard in this server and audit-logs every change.
 */
@BService
public class LanguageAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(LanguageAdminApi.class);

    private final Messages messages;

    public LanguageAdminApi(Messages messages) {
        this.messages = messages;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("language")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        DataArray languages = DataArray.empty();
        messages.available().forEach((code, name) -> languages.add(DataObject.empty().put("code", code).put("name", name)));
        return DataObject.empty().put("language", messages.languageOf(guild.getIdLong())).put("languages", languages);
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        String code = body.getString("language", "").strip();
        if (!messages.isAvailable(code)) throw new ApiError(400, Text.of(guild, "dashboard.that_language_isn_t_available"));
        messages.setLanguage(guild.getIdLong(), code);
        log.info("Dashboard: {} set the language of {} to {}", actor.getId(), guild.getId(), code);
        return get(guild);
    }
}
