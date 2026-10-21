package com.younglings.bot.rsnlink.bridge;

import com.younglings.bot.rsnlink.LinkDmSender;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Sends the DM fallback's code through JonnyBot's gateway connection (LINK-SYSTEM.md sections 3.2, 4.2 and 13.3). Discord only lets a bot
 * DM someone it shares a server with who allows DMs from that server's members, so a refused send is normal and simply returns false. Never
 * logs the ids or usernames it is given. Runs on the link service's background thread, never while a plugin request waits.
 */
public final class JdaLinkDmSender implements LinkDmSender {
    private static final Logger log = LoggerFactory.getLogger(JdaLinkDmSender.class);
    static final String NOT_ME = "rsnlink:notme:";
    static final String STOP = "rsnlink:stop:";

    private volatile JDA jda;

    void attach(JDA jda) {
        this.jda = jda;
    }

    /**
     * Exact username only (never display names or nicknames): first the bot's user cache, then each shared server's member search, stopping
     * at the first server with a match. A name that matches two different accounts counts as not found.
     */
    @Override
    public Optional<String> resolveUsername(String username) {
        JDA current = jda;
        if (current == null) return Optional.empty();
        Set<Long> found = new HashSet<>();
        current.getUserCache().forEach(u -> {
            if (u.getName().equals(username)) found.add(u.getIdLong());
        });
        if (found.isEmpty()) {
            for (Guild guild : current.getGuilds()) {
                try {
                    List<Member> members = guild.retrieveMembersByPrefix(username, 10).get();
                    for (Member m : members) if (m.getUser().getName().equals(username)) found.add(m.getIdLong());
                } catch (RuntimeException e) {
                    log.debug("Member search failed in one server ({})", e.getClass().getSimpleName());
                }
                if (!found.isEmpty()) break;
            }
        }
        return found.size() == 1 ? Optional.of(Long.toString(found.iterator().next())) : Optional.empty();
    }

    @Override
    public boolean sendCode(String discordId, String rsnDisplay, String userCode, String pairingId, long expiresInMinutes) {
        JDA current = jda;
        if (current == null) return false;
        try {
            User user = current.retrieveUserById(discordId).complete();
            if (user.isBot()) return false;
            user.openPrivateChannel()
                    .flatMap(channel -> channel.sendMessage(text(rsnDisplay, userCode, expiresInMinutes))
                            .setComponents(ActionRow.of(Button.secondary(NOT_ME + pairingId, "Not me"), Button.danger(STOP + pairingId, "Stop these messages"))))
                    .timeout(15, TimeUnit.SECONDS)
                    .complete();
            return true;
        } catch (RuntimeException e) {
            // Usually 50007 "Cannot send messages to this user" (no shared server, or DMs closed) or an unknown user.
            return false;
        }
    }

    /** Section 13.3: neutral, no clan name, no invite, no links. RSNs hold only letters, digits, space, - and _, so escaping _ is enough. */
    static String text(String rsnDisplay, String userCode, long expiresInMinutes) {
        return "Someone playing RuneScape as **" + rsnDisplay.replace("_", "\\_") + "** asked to link this Discord account using the RS Link plugin.\n"
                + "If that was you, type this code into the plugin: **" + userCode + "** (expires in " + expiresInMinutes + " minutes).\n"
                + "If it wasn't you, ignore this message. Nothing is linked without the code.";
    }
}
