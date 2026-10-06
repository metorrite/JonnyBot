package com.younglings.bot.internal;

import com.younglings.bot.database.SchemaBootstrapper;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.tree.ComponentTree;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The website's news block: the latest posts from Discord channels an admin has chosen. The channel list is
 * stored in the database; the messages themselves are read live from Discord and kept in a short in-memory
 * cache so a busy site never turns into a flood of Discord requests. Nothing here is state that matters across
 * a restart — the cache just refills.
 * <p>
 * Everything in a chosen channel becomes publicly visible on the website, which is why only an admin can pick
 * channels (and the dashboard says so plainly).
 */
@BService
public class SiteNewsService {
    private static final Logger log = LoggerFactory.getLogger(SiteNewsService.class);
    private static final long CACHE_MILLIS = 60_000;
    private static final int PER_CHANNEL = 8;
    private static final Pattern USER = Pattern.compile("<@!?(\\d+)>");
    private static final Pattern ROLE = Pattern.compile("<@&(\\d+)>");
    private static final Pattern CHANNEL = Pattern.compile("<#(\\d+)>");
    private static final Pattern EMOJI = Pattern.compile("<a?:(\\w+):\\d+>");

    private final ConnectionSupplier connectionSupplier;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    public SiteNewsService(ConnectionSupplier connectionSupplier) {
        this.connectionSupplier = connectionSupplier;
        SchemaBootstrapper.run(connectionSupplier, log, "site news", List.of(
                "CREATE SCHEMA IF NOT EXISTS younglings;",
                """
                CREATE TABLE IF NOT EXISTS younglings.site_news_channel (
                    guild_id BIGINT NOT NULL,
                    channel_id BIGINT NOT NULL,
                    label TEXT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (guild_id, channel_id)
                );
                """));
    }

    private record Cached(long fetchedAt, List<DataObject> posts) {}

    public record NewsChannel(long channelId, String label, int position) {}

    // ---------- which channels ----------

    public List<NewsChannel> channels(long guildId) {
        try (Connection c = connectionSupplier.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT channel_id, label, position FROM younglings.site_news_channel WHERE guild_id = ? ORDER BY position, channel_id")) {
            s.setLong(1, guildId);
            List<NewsChannel> channels = new ArrayList<>();
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) channels.add(new NewsChannel(rs.getLong("channel_id"), rs.getString("label"), rs.getInt("position")));
            }
            return channels;
        } catch (SQLException e) {
            throw fail("read news channels", e);
        }
    }

    public void replaceChannels(long guildId, List<NewsChannel> channels) {
        try (Connection c = connectionSupplier.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement delete = c.prepareStatement("DELETE FROM younglings.site_news_channel WHERE guild_id = ?")) {
                    delete.setLong(1, guildId);
                    delete.executeUpdate();
                }
                try (PreparedStatement insert = c.prepareStatement("INSERT INTO younglings.site_news_channel (guild_id, channel_id, label, position) VALUES (?, ?, ?, ?)")) {
                    int position = 0;
                    for (NewsChannel channel : channels) {
                        insert.setLong(1, guildId);
                        insert.setLong(2, channel.channelId());
                        insert.setString(3, channel.label());
                        insert.setInt(4, position++);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw fail("save news channels", e);
        }
        cache.clear();
    }

    // ---------- the posts ----------

    /** The newest posts across the chosen channels, newest first, at most {@code limit}. */
    public DataArray latest(Guild guild, int limit) {
        List<DataObject> all = new ArrayList<>();
        for (NewsChannel configured : channels(guild.getIdLong())) {
            all.addAll(postsFor(guild, configured));
        }
        all.sort(Comparator.comparing((DataObject p) -> p.getString("postedAt")).reversed());

        DataArray array = DataArray.empty();
        all.stream().limit(limit).forEach(array::add);
        return array;
    }

    private List<DataObject> postsFor(Guild guild, NewsChannel configured) {
        Cached cached = cache.get(configured.channelId());
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < CACHE_MILLIS) return cached.posts();

        List<DataObject> posts = new ArrayList<>();
        GuildMessageChannel channel = guild.getChannelById(GuildMessageChannel.class, configured.channelId());
        if (channel != null) {
            try {
                for (Message message : channel.getHistory().retrievePast(PER_CHANNEL).complete()) {
                    DataObject post = toPost(guild, channel, configured, message);
                    if (post != null) posts.add(post);
                }
            } catch (Exception e) {
                log.warn("Couldn't read news channel {}: {}", configured.channelId(), e.getMessage());
                if (cached != null) return cached.posts(); // keep showing the last good copy
            }
        }
        cache.put(configured.channelId(), new Cached(System.currentTimeMillis(), posts));
        return posts;
    }

    /** Resolves Discord's raw mention syntax into readable text. */
    static String clean(Guild guild, String text) {
        if (text == null) return "";
        text = replaceAll(USER, text, m -> {
            var member = guild.getMemberById(Long.parseLong(m.group(1)));
            return member == null ? "@member" : "@" + member.getEffectiveName();
        });
        text = replaceAll(ROLE, text, m -> {
            var role = guild.getRoleById(Long.parseLong(m.group(1)));
            return role == null ? "@role" : "@" + role.getName();
        });
        text = replaceAll(CHANNEL, text, m -> {
            var channel = guild.getGuildChannelById(Long.parseLong(m.group(1)));
            return channel == null ? "#channel" : "#" + channel.getName();
        });
        text = replaceAll(EMOJI, text, m -> ":" + m.group(1) + ":");
        return text.replace("@everyone", "@​everyone").replace("@here", "@​here");
    }

    private static String replaceAll(Pattern pattern, String text, java.util.function.Function<Matcher, String> replacement) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(replacement.apply(m)));
        m.appendTail(out);
        return out.toString();
    }

    private DataObject toPost(Guild guild, GuildMessageChannel channel, NewsChannel configured, Message message) {
        // Plain text, plus the text and pictures inside Components V2 messages (which have no "content").
        List<String> parts = new ArrayList<>();
        if (!message.getContentRaw().isBlank()) parts.add(message.getContentRaw());
        List<String> images = new ArrayList<>();

        var tree = ComponentTree.of(message.getComponents());
        for (TextDisplay text : tree.findAll(TextDisplay.class)) if (!text.getContent().isBlank()) parts.add(text.getContent());
        for (MediaGallery gallery : tree.findAll(MediaGallery.class)) for (MediaGalleryItem item : gallery.getItems()) images.add(item.getUrl());
        for (Message.Attachment attachment : message.getAttachments()) if (attachment.isImage()) images.add(attachment.getUrl());

        DataArray embeds = DataArray.empty();
        for (MessageEmbed embed : message.getEmbeds()) {
            DataArray fields = DataArray.empty();
            embed.getFields().forEach(f -> fields.add(DataObject.empty().put("name", clean(guild, f.getName())).put("value", clean(guild, f.getValue())).put("inline", f.isInline())));
            embeds.add(DataObject.empty()
                    .put("title", clean(guild, embed.getTitle())).put("description", clean(guild, embed.getDescription())).put("url", embed.getUrl())
                    .put("color", embed.getColorRaw() & 0xFFFFFF)
                    .put("image", embed.getImage() == null ? null : embed.getImage().getUrl())
                    .put("thumbnail", embed.getThumbnail() == null ? null : embed.getThumbnail().getUrl())
                    .put("author", embed.getAuthor() == null ? null : embed.getAuthor().getName())
                    .put("footer", embed.getFooter() == null ? null : embed.getFooter().getText())
                    .put("fields", fields));
        }

        String text = clean(guild, String.join("\n\n", parts)).strip();
        if (text.isEmpty() && images.isEmpty() && embeds.length() == 0) return null; // nothing readable (a bare sticker, a join notice…)

        DataArray imageArray = DataArray.empty();
        images.forEach(imageArray::add);

        var member = message.getMember();
        String author = member != null ? member.getEffectiveName() : message.getAuthor().getName();
        String avatar = member != null ? member.getEffectiveAvatarUrl() : message.getAuthor().getEffectiveAvatarUrl();

        return DataObject.empty()
                .put("id", message.getId())
                .put("channel", configured.label() == null || configured.label().isBlank() ? channel.getName() : configured.label())
                .put("author", author).put("avatarUrl", avatar)
                .put("postedAt", message.getTimeCreated().toString())
                .put("editedAt", message.getTimeEdited() == null ? null : message.getTimeEdited().toString())
                .put("text", text).put("images", imageArray).put("embeds", embeds)
                .put("pinned", message.isPinned())
                .put("url", "https://discord.com/channels/" + guild.getId() + "/" + channel.getId() + "/" + message.getId());
    }

    private static RuntimeException fail(String action, SQLException e) {
        log.error("Failed to {}", action, e);
        return new RuntimeException("Failed to " + action, e);
    }
}
