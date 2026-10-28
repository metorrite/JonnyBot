package com.runeclans.bot.lookup;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.List;
import java.util.Optional;

/** The wiki's table of Distractions and Diversions (see {@link DndTable}), cached by {@link LookupHttp}. Empty when the wiki can't be reached or its layout changed. */
@BService
public class RecurringEventsService {
    private final LookupHttp http;

    public RecurringEventsService(LookupHttp http) {
        this.http = http;
    }

    public Optional<List<DndTable.Entry>> all() {
        return http.get(DndTable.URL).map(DndTable::parse).filter(rows -> !rows.isEmpty());
    }

    /** The entries of one wiki category ("When available", "Daily", "Weekly", "Monthly", "Item"). */
    public Optional<List<DndTable.Entry>> category(String category) {
        return all().map(rows -> rows.stream().filter(e -> e.category().equalsIgnoreCase(category)).toList());
    }
}
