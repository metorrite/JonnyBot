package com.runeclans.bot.internal;

import com.runeclans.bot.commandusage.CommandUsageRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/**
 * The dashboard Commands page's usage figures (T-191): how many times each slash command was used in this server over the last
 * {@value #DAYS} days, keyed by its top-level slash name. Read-only. Reached only through {@link TicketAdminApi}, which has already checked
 * the asker may use the dashboard in this server.
 */
@BService
public class CommandUsageAdminApi implements AdminRouteExtension {
    static final int DAYS = 30;

    private final CommandUsageRepository repository;

    public CommandUsageAdminApi(CommandUsageRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("command-usage")) return null;
        AdminRouteExtension.requireMethod(method, "GET");
        LocalDate since = LocalDate.now(ZoneOffset.UTC).minusDays(DAYS - 1);
        DataObject counts = DataObject.empty();
        repository.totals(guild.getIdLong(), since).forEach(counts::put);
        return DataObject.empty().put("days", DAYS).put("since", since.toString()).put("counts", counts);
    }
}
