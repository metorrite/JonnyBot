package com.younglings.bot.internal;

import com.younglings.bot.internal.TicketAdminApi.ApiError;
import com.younglings.bot.welcome.WelcomeConfig;
import com.younglings.bot.welcome.WelcomeConfig.EmbedField;
import com.younglings.bot.welcome.WelcomeConfig.MessageType;
import com.younglings.bot.welcome.WelcomeRepository;
import com.younglings.bot.welcome.WelcomeService;
import com.younglings.bot.welcome.WelcomeValidator;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The website's side of the welcome message: read it, save it, and send a test. Reached only through
 * {@link TicketAdminApi}, which has already verified the acting user is an Admin or Developer, and which writes every
 * change to the audit log. The welcome is edited only here, never from Discord, because its text is long.
 */
@BService
public class WelcomeAdminApi {
    private static final Logger log = LoggerFactory.getLogger(WelcomeAdminApi.class);
    private static final int MAX_FIELDS_READ = 40; // read a few over the limit so the validator can say "too many" instead of silently dropping them

    private final WelcomeRepository repository;
    private final WelcomeService service;

    public WelcomeAdminApi(WelcomeRepository repository, WelcomeService service) {
        this.repository = repository;
        this.service = service;
    }

    DataObject get(Guild guild) {
        return toJson(guild, repository.get(guild.getIdLong()));
    }

    DataObject save(Guild guild, Member actor, DataObject body) {
        WelcomeConfig config = parseAndCheck(guild, body);
        repository.save(config);
        log.info("Dashboard: {} saved the welcome message (enabled={}, type={})", actor.getId(), config.enabled(), config.messageType());
        return get(guild);
    }

    /** Sends the draft in the request, saved or not, as if the acting admin had just joined. */
    DataObject test(Guild guild, Member actor, DataObject body) {
        WelcomeConfig config = parseAndCheck(guild, body);
        if (config.channelId() == null) throw new ApiError(400, "Choose the welcome channel first.");
        try {
            WelcomeService.TestResult result = service.sendTest(guild, actor, config);
            log.info("Dashboard: {} sent a welcome test in #{}", actor.getId(), result.channelName());
            return DataObject.empty().put("sent", true).put("channelName", result.channelName()).put("dmSent", result.dmSent());
        } catch (IllegalStateException e) {
            throw new ApiError(400, e.getMessage());
        }
    }

    // ---------- reading the request ----------

    private WelcomeConfig parseAndCheck(Guild guild, DataObject body) {
        WelcomeConfig config = parse(guild.getIdLong(), body);
        List<String> problems = new ArrayList<>(WelcomeValidator.validate(config));
        if (config.channelId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, config.channelId());
            if (channel == null) problems.add("That channel doesn't exist in this server.");
            else if (!channel.canTalk()) problems.add("JonnyBot can't post in #" + channel.getName() + ". It needs permission to view and send messages (and embed links) there.");
        }
        if (!problems.isEmpty()) throw new ApiError(400, "That welcome message can't be saved yet.", problems);
        return config;
    }

    static WelcomeConfig parse(long guildId, DataObject b) {
        MessageType type;
        try {
            type = MessageType.valueOf(b.getString("messageType", "EMBED_TEXT"));
        } catch (IllegalArgumentException e) {
            throw new ApiError(400, "Choose a message type.");
        }

        Long channelId = null;
        if (!b.isNull("channelId")) {
            String raw = b.getString("channelId", "").strip();
            if (!raw.matches("\\d{1,20}")) throw new ApiError(400, "That isn't a valid channel.");
            channelId = Long.parseLong(raw);
        }

        Integer color = null;
        if (!b.isNull("color")) {
            color = b.getInt("color", -1);
            if (color < 0 || color > 0xFFFFFF) throw new ApiError(400, "The embed colour isn't a valid colour.");
        }

        List<EmbedField> fields = new ArrayList<>();
        if (!b.isNull("fields")) {
            DataArray array = b.getArray("fields");
            for (int i = 0; i < Math.min(array.length(), MAX_FIELDS_READ); i++) {
                DataObject f = array.getObject(i);
                fields.add(new EmbedField(f.getString("name", "").strip(), f.getString("value", "").strip(), f.getBoolean("inline", false)));
            }
        }

        return new WelcomeConfig(guildId, b.getBoolean("enabled", false), type, channelId, b.getBoolean("alsoDm", false),
                b.getString("content", "").stripTrailing(), color, b.getString("title", "").strip(), b.getString("titleUrl", "").strip(),
                b.getString("description", "").stripTrailing(), b.getString("authorName", "").strip(), b.getString("authorIconUrl", "").strip(),
                b.getString("thumbnailUrl", "").strip(), b.getString("imageUrl", "").strip(), b.getString("footerText", "").strip(),
                b.getString("footerIconUrl", "").strip(), fields, b.getBoolean("linkButton", false),
                b.getString("linkButtonLabel", "Link your RuneScape name").strip(), b.getString("linkButtonStyle", "primary").strip().toLowerCase());
    }

    // ---------- writing the answer ----------

    static DataObject toJson(Guild guild, WelcomeConfig c) {
        DataArray fields = DataArray.empty();
        for (EmbedField f : c.fields()) {
            fields.add(DataObject.empty().put("name", f.name()).put("value", f.value()).put("inline", f.inline()));
        }
        String channelName = null;
        if (c.channelId() != null) {
            GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, c.channelId());
            channelName = channel == null ? null : channel.getName();
        }
        return DataObject.empty()
                .put("enabled", c.enabled())
                .put("messageType", c.messageType().name())
                .put("channelId", c.channelId() == null ? null : Long.toString(c.channelId()))
                .put("channelName", channelName)
                .put("alsoDm", c.alsoDm())
                .put("content", c.content())
                .put("color", c.color())
                .put("title", c.title())
                .put("titleUrl", c.titleUrl())
                .put("description", c.description())
                .put("authorName", c.authorName())
                .put("authorIconUrl", c.authorIconUrl())
                .put("thumbnailUrl", c.thumbnailUrl())
                .put("imageUrl", c.imageUrl())
                .put("footerText", c.footerText())
                .put("footerIconUrl", c.footerIconUrl())
                .put("fields", fields)
                .put("linkButton", c.linkButton())
                .put("linkButtonLabel", c.linkButtonLabel())
                .put("linkButtonStyle", c.linkButtonStyle())
                // for the editor's preview only; never saved
                .put("serverName", guild.getName())
                .put("memberCount", guild.getMemberCount())
                .put("serverIconUrl", guild.getIconUrl());
    }
}
