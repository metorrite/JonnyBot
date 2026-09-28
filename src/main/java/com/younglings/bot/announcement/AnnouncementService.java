package com.younglings.bot.announcement;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.List;

/** Typed wrapper around {@link AnnouncementRepository} — every caller works with {@link AnnouncementPreset} instead of its raw enum name. */
@BService
public class AnnouncementService {
    private final AnnouncementRepository repository;

    public AnnouncementService(AnnouncementRepository repository) {
        this.repository = repository;
    }

    public String getText(long guildId, AnnouncementPreset preset) {
        return repository.getText(guildId, preset.name());
    }

    public void setText(long guildId, AnnouncementPreset preset, String text) {
        repository.setText(guildId, preset.name(), text);
    }

    public List<AnnouncementRepository.Destination> getDestinations(long guildId, AnnouncementPreset preset) {
        return repository.getDestinations(guildId, preset.name());
    }

    public void addDestination(long guildId, AnnouncementPreset preset, long channelId) {
        repository.addDestination(guildId, preset.name(), channelId);
    }

    public void removeDestination(long guildId, long destinationId) {
        repository.removeDestination(guildId, destinationId);
    }

    public void clearDestinations(long guildId, AnnouncementPreset preset) {
        repository.clearDestinations(guildId, preset.name());
    }

    public void setMessageId(long guildId, long destinationId, Long messageId) {
        repository.setMessageId(guildId, destinationId, messageId);
    }
}
