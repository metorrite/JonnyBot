package com.runeclans.bot.events;

import java.util.List;

/** Why the engine refused something, in words an admin can read. {@code status} is the HTTP-style kind: 400 bad input, 404 not found, 409 not allowed now. */
public class EventProblem extends RuntimeException {
    private final int status;
    private final List<String> problems;

    public EventProblem(int status, String message) {
        this(status, message, List.of());
    }

    public EventProblem(int status, String message, List<String> problems) {
        super(message);
        this.status = status;
        this.problems = List.copyOf(problems);
    }

    public int status() {
        return status;
    }

    public List<String> problems() {
        return problems;
    }
}
