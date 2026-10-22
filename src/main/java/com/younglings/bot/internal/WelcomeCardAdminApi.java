package com.younglings.bot.internal;

import com.younglings.bot.i18n.Text;
import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.welcome.WelcomeCardConfig;
import com.younglings.bot.welcome.WelcomeCardRepository;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The website's side of the optional welcome picture: read it and save it. Reached only through {@link TicketAdminApi}, which has already
 * checked the asker may use the dashboard in this server and audit-logs every write. The picture is drawn when someone joins (or when the
 * welcome's test is sent), so there is no separate test here.
 */
@BService
public class WelcomeCardAdminApi implements AdminRouteExtension {
    private static final Logger log = LoggerFactory.getLogger(WelcomeCardAdminApi.class);

    private final WelcomeCardRepository repository;

    public WelcomeCardAdminApi(WelcomeCardRepository repository) {
        this.repository = repository;
    }

    @Override
    public DataObject route(Guild guild, Member actor, String method, String[] parts, Supplier<DataObject> body) {
        if (parts.length != 1 || !parts[0].equals("welcome-card")) return null;
        if (method.equals("GET")) return get(guild);
        AdminRouteExtension.requireMethod(method, "PUT");
        return save(guild, actor, body.get());
    }

    DataObject get(Guild guild) {
        return toJson(guild, repository.get(guild.getIdLong()));
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        WelcomeCardConfig current = repository.get(guild.getIdLong());
        List<String> problems = new ArrayList<>();
        int background = color(body, "backgroundColor", current.backgroundColor(), "The background colour", problems);
        int text = color(body, "textColor", current.textColor(), "The text colour", problems);
        String image = body.hasKey("backgroundImageUrl") ? body.getString("backgroundImageUrl", "").strip() : current.backgroundImageUrl();
        if (!image.isEmpty() && (!image.toLowerCase().startsWith("https://") || image.length() > WelcomeCardConfig.MAX_URL)) {
            problems.add("The background picture must be a full address starting with https:// (or an upload).");
        }
        String title = body.hasKey("title") ? body.getString("title", "").strip() : current.title();
        String description = body.hasKey("description") ? body.getString("description", "").strip() : current.description();
        if (title.length() > WelcomeCardConfig.MAX_TITLE) problems.add("The title can be at most " + WelcomeCardConfig.MAX_TITLE + " characters.");
        if (description.length() > WelcomeCardConfig.MAX_DESCRIPTION) problems.add("The description can be at most " + WelcomeCardConfig.MAX_DESCRIPTION + " characters.");
        boolean enabled = body.hasKey("enabled") ? body.getBoolean("enabled", false) : current.enabled();
        if (!problems.isEmpty()) throw new ApiError(400, Text.of(guild, "dashboard.that_welcome_picture_can_t_be"), problems);

        repository.save(new WelcomeCardConfig(guild.getIdLong(), enabled, background, image, text, title, description));
        log.info("Dashboard: {} saved the welcome picture ({})", actor.getId(), enabled ? "on" : "off");
        return get(guild);
    }

    private static int color(DataObject body, String key, int fallback, String what, List<String> problems) {
        if (!body.hasKey(key) || body.isNull(key)) return fallback;
        int value = body.getInt(key, -1);
        if (value < 0 || value > 0xFFFFFF) {
            problems.add(what + " isn't a valid colour.");
            return fallback;
        }
        return value;
    }

    static DataObject toJson(Guild guild, WelcomeCardConfig c) {
        return DataObject.empty().put("enabled", c.enabled()).put("backgroundColor", c.backgroundColor()).put("backgroundImageUrl", c.backgroundImageUrl())
                .put("textColor", c.textColor()).put("title", c.title()).put("description", c.description())
                // preview only; never saved
                .put("serverName", guild.getName()).put("memberCount", guild.getMemberCount());
    }
}
