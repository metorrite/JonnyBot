package com.runeclans.bot.util;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * A name for this bot process, new on every start (T-696): the host name, the process id and a random part, so two copies on one machine
 * or a restarted copy never share it. Rows a process claims (the Discord job outbox) carry it, so another process can tell its own work from
 * work a live or dead copy took. Not a secret, and never reused.
 */
public final class ProcessId {
    private static final String ID = build();

    private ProcessId() {}

    /** This process's name, at most 64 characters. */
    public static String get() {
        return ID;
    }

    private static String build() {
        String host = System.getenv("HOSTNAME");
        host = host == null ? "" : host.replaceAll("[^A-Za-z0-9._-]", "");
        if (host.isEmpty()) host = "bot";
        if (host.length() > 32) host = host.substring(0, 32);
        byte[] random = new byte[4];
        new SecureRandom().nextBytes(random);
        return host + "-" + ProcessHandle.current().pid() + "-" + HexFormat.of().formatHex(random);
    }
}
