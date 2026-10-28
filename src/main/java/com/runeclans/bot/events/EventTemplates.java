package com.runeclans.bot.events;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Every event template the bot knows, found by key. */
@BService
public class EventTemplates {
    private final List<EventTemplate> templates;

    public EventTemplates(List<EventTemplate> templates) {
        this.templates = templates.stream().sorted(Comparator.comparing(EventTemplate::label)).toList();
    }

    public List<EventTemplate> all() {
        return templates;
    }

    public Optional<EventTemplate> byKey(String key) {
        return templates.stream().filter(t -> t.key().equals(key)).findFirst();
    }
}
