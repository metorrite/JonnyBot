package com.runeclans.bot.events;

import com.runeclans.bot.events.EventRules.ObjectiveSpec;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;

/** The escape hatch: no settings and no starter objectives. A leader builds the event from raw objectives, so nothing they want is blocked. */
@BService
public class CustomTemplate implements EventTemplate {
    public static final String KEY = "custom";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String label() {
        return "Custom event";
    }

    @Override
    public String description() {
        return "Start from nothing: add your own objectives, points and prerequisites. Everything the other templates do is built from these.";
    }

    @Override
    public List<String> configProblems(DataObject config) {
        return List.of();
    }

    @Override
    public List<ObjectiveSpec> starterObjectives(DataObject config) {
        return List.of();
    }
}
