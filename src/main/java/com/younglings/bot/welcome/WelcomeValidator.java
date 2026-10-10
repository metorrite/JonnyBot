package com.younglings.bot.welcome;

import com.younglings.bot.welcome.WelcomeConfig.EmbedField;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a welcome message against Discord's limits before it is saved, so a message that could never be posted
 * is refused with a plain reason instead of failing for the first person who joins. Text limits are checked on the
 * template as typed, with headroom left for what the variables expand to.
 */
public final class WelcomeValidator {
    private WelcomeValidator() {}

    static final int MAX_CONTENT = 1800;      // Discord allows 2000; leaves room for a long {user} or {server}
    static final int MAX_TITLE = 256;
    static final int MAX_DESCRIPTION = 3900;  // Discord allows 4096
    static final int MAX_AUTHOR = 256;
    static final int MAX_FOOTER = 2048;
    static final int MAX_FIELDS = 25;
    static final int MAX_FIELD_NAME = 256;
    static final int MAX_FIELD_VALUE = 1024;
    static final int MAX_EMBED_TOTAL = 5500;  // Discord allows 6000 across the whole embed
    static final int MAX_URL = 2000;
    static final int MAX_BUTTON_LABEL = 80;
    static final int MAX_CONTAINER_TOTAL = 3800; // Discord allows 4000 characters of text across a container

    /** Every reason this message can't be saved as it is; empty when it is fine. */
    public static List<String> validate(WelcomeConfig c) {
        List<String> problems = new ArrayList<>();

        if (c.enabled() && c.channelId() == null) problems.add("Choose the welcome channel before turning the welcome on.");

        boolean text = c.messageType().hasText();
        boolean embed = c.messageType().hasEmbed();

        if (text && c.content().isBlank() && !embed) problems.add("The message is empty. Write something to send.");
        if (c.content().length() > MAX_CONTENT) problems.add("The message text can be at most " + MAX_CONTENT + " characters (it is " + c.content().length() + ").");

        if (embed) {
            if (!hasEmbedContent(c) && (!text || c.content().isBlank())) problems.add("The embed is empty. Add a title, a description, an image or a field.");
            tooLong(problems, "The embed title", c.title(), MAX_TITLE);
            tooLong(problems, "The embed description", c.description(), MAX_DESCRIPTION);
            tooLong(problems, "The author name", c.authorName(), MAX_AUTHOR);
            tooLong(problems, "The footer text", c.footerText(), MAX_FOOTER);

            if (!c.titleUrl().isBlank() && c.title().isBlank()) problems.add("A title link needs a title.");
            if (!c.authorIconUrl().isBlank() && c.authorName().isBlank()) problems.add("An author icon needs an author name.");
            if (!c.footerIconUrl().isBlank() && c.footerText().isBlank()) problems.add("A footer icon needs footer text.");
            url(problems, "The title link", c.titleUrl());
            url(problems, "The author icon", c.authorIconUrl());
            url(problems, "The thumbnail", c.thumbnailUrl());
            url(problems, "The image", c.imageUrl());
            url(problems, "The footer icon", c.footerIconUrl());

            if (c.color() != null && (c.color() < 0 || c.color() > 0xFFFFFF)) problems.add("The embed colour isn't a valid colour.");

            if (c.fields().size() > MAX_FIELDS) problems.add("An embed can have at most " + MAX_FIELDS + " fields.");
            int number = 0;
            for (EmbedField field : c.fields()) {
                number++;
                if (field.name().isBlank() || field.value().isBlank()) problems.add("Field " + number + " needs both a name and a value.");
                tooLong(problems, "The name of field " + number, field.name(), MAX_FIELD_NAME);
                tooLong(problems, "The value of field " + number, field.value(), MAX_FIELD_VALUE);
            }

            int total = c.title().length() + c.description().length() + c.authorName().length() + c.footerText().length();
            for (EmbedField field : c.fields()) total += field.name().length() + field.value().length();
            if (total > MAX_EMBED_TOTAL) problems.add("The embed is too long overall (" + total + " characters; Discord's limit is 6000 including what the variables fill in).");
        }

        if (c.linkButton()) {
            if (c.linkButtonLabel().isBlank()) problems.add("The link button needs a label.");
            else if (c.linkButtonLabel().length() > MAX_BUTTON_LABEL) problems.add("The link button label can be at most " + MAX_BUTTON_LABEL + " characters.");
            if (!WelcomeConfig.BUTTON_STYLES.contains(c.linkButtonStyle())) problems.add("Choose a colour for the link button.");
        }

        buttonMarker(problems, c);
        if (c.messageType().isContainer()) {
            int total = c.title().length() + c.description().length() + c.authorName().length() + c.footerText().length();
            for (EmbedField field : c.fields()) total += field.name().length() + field.value().length();
            if (total > MAX_CONTAINER_TOTAL) problems.add("The message is too long overall (" + total + " characters; a container can hold about " + MAX_CONTAINER_TOTAL + " including what the variables fill in).");
        }
        return problems;
    }

    /**
     * The {@code {rs_button}} variable puts the link button inside a container, so it needs one, and the button turned on, and a place a
     * block of text can hold it: the description, a field's value or the footer.
     */
    private static void buttonMarker(List<String> problems, WelcomeConfig c) {
        String marker = ContainerWelcome.BUTTON_MARKER;
        boolean anywhere = c.content().contains(marker) || c.title().contains(marker) || c.titleUrl().contains(marker) || c.authorName().contains(marker)
                || c.description().contains(marker) || c.footerText().contains(marker)
                || c.fields().stream().anyMatch(f -> f.name().contains(marker) || f.value().contains(marker));
        if (!anywhere) return;

        if (!c.messageType().isContainer()) {
            problems.add(marker + " only works in a Container message: an embed or a plain message can't hold a button inside it. Choose Container, or remove " + marker + ".");
            return;
        }
        if (!c.linkButton()) problems.add(marker + " needs the link button turned on.");
        if (c.title().contains(marker) || c.authorName().contains(marker) || c.titleUrl().contains(marker) || c.fields().stream().anyMatch(f -> f.name().contains(marker))) {
            problems.add(marker + " can go in the description, a field's value or the footer, not in a title, author or field name.");
        }
    }

    /** True if the embed has anything in it worth sending. */
    public static boolean hasEmbedContent(WelcomeConfig c) {
        return !c.title().isBlank() || !c.description().isBlank() || !c.authorName().isBlank() || !c.imageUrl().isBlank()
                || !c.thumbnailUrl().isBlank() || !c.footerText().isBlank() || !c.fields().isEmpty();
    }

    private static void tooLong(List<String> problems, String what, String value, int max) {
        if (value.length() > max) problems.add(what + " can be at most " + max + " characters (it is " + value.length() + ").");
    }

    /** A blank address is fine (nothing to show); anything else must be a web address, or the {avatar} variable. */
    private static void url(List<String> problems, String what, String value) {
        if (value.isBlank() || value.strip().equals("{avatar}")) return;
        if (value.length() > MAX_URL) {
            problems.add(what + " address is too long.");
            return;
        }
        try {
            URI uri = new URI(value.strip());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")) || uri.getHost() == null) {
                problems.add(what + " must be a full web address starting with https://.");
            }
        } catch (Exception e) {
            problems.add(what + " must be a full web address starting with https://.");
        }
    }
}
