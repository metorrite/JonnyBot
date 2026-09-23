package com.younglings.bot.runescape;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A randomly-assigned Makeover Mage appearance for RSN verification — the player applies this
 * combination in-game, and an admin compares it against their fetched avatar image before
 * confirming the link. The option names here are a representative approximation of RS3's actual
 * Makeover Mage menu, not verified against a live client — close enough for a human to recognize
 * and match, but let me know if you want them corrected to the exact in-game wording/order.
 */
public record MakeoverAppearance(String hairstyle, String hairColor, String skinTone) {
    private static final List<String> HAIRSTYLES = List.of(
            "Afro", "Bald", "Bob Cut", "Long Straight", "Ponytail",
            "Spiky", "Mohawk", "Curly", "Braided", "Short Crop"
    );

    private static final List<String> HAIR_COLORS = List.of(
            "Black", "Brown", "Blonde", "Red", "White",
            "Blue", "Green", "Purple", "Pink", "Orange"
    );

    private static final List<String> SKIN_TONES = List.of(
            "Pale", "Light", "Tan", "Medium", "Dark", "Deep Dark"
    );

    public static MakeoverAppearance random() {
        var random = ThreadLocalRandom.current();
        return new MakeoverAppearance(
                HAIRSTYLES.get(random.nextInt(HAIRSTYLES.size())),
                HAIR_COLORS.get(random.nextInt(HAIR_COLORS.size())),
                SKIN_TONES.get(random.nextInt(SKIN_TONES.size()))
        );
    }

    public String describe() {
        return "Hairstyle: **" + hairstyle + "**, Hair Color: **" + hairColor + "**, Skin Tone: **" + skinTone + "**";
    }
}
