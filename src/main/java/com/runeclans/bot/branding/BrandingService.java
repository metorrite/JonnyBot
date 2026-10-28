package com.runeclans.bot.branding;

import com.runeclans.bot.configure.GuildSettingsService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;

/**
 * What a server calls itself in JonnyBot's posts. In order: the display name the server chose on the dashboard, the clan name set for the
 * server, the Discord server's own name. Nothing is ever hard-coded to one clan, so any server's messages read as that server's own.
 */
@BService
public class BrandingService {
    public static final int MAX_DISPLAY_NAME = 40;

    private final BrandingRepository repository;
    private final GuildSettingsService settings;

    public BrandingService(BrandingRepository repository, GuildSettingsService settings) {
        this.repository = repository;
        this.settings = settings;
    }

    /** The server's chosen display name, or {@code null} when it has not set one. */
    public String customName(long guildId) {
        String name = repository.displayName(guildId);
        return name == null || name.isBlank() ? null : name;
    }

    /** The clan name set for this server, or {@code null} when it has no clan. */
    public String clanName(long guildId) {
        String name = settings.getEffective(guildId).clanName();
        return name == null || name.isBlank() ? null : name;
    }

    /** The name to use in text: display name, then clan name, then the Discord server's name. */
    public String displayName(Guild guild) {
        String custom = customName(guild.getIdLong());
        if (custom != null) return custom;
        String clan = clanName(guild.getIdLong());
        return clan != null ? clan : guild.getName();
    }
}
