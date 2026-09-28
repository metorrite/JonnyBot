package com.younglings.bot.tracking;

import io.github.freya022.botcommands.api.core.service.annotations.BService;

import java.util.List;

/** Thin pass-through to {@link TrackingRepository}, typed against {@link TrackingGroup} instead of raw strings — the read path {@code /configure}'s Tracking panel uses. */
@BService
public class TrackingService {
    private final TrackingRepository repository;

    public TrackingService(TrackingRepository repository) {
        this.repository = repository;
    }

    public boolean isEnabled(long guildId, TrackingGroup group) {
        return repository.isEnabled(guildId, group.name());
    }

    public void setEnabled(long guildId, TrackingGroup group, boolean enabled) {
        repository.setEnabled(guildId, group.name(), enabled);
    }

    public List<TrackingRepository.Destination> getDestinations(long guildId, TrackingGroup group) {
        return repository.getDestinations(guildId, group.name());
    }

    public void addDestination(long guildId, TrackingGroup group, long channelId) {
        repository.addDestination(guildId, group.name(), channelId);
    }

    public void removeDestination(long guildId, long destinationId) {
        repository.removeDestination(guildId, destinationId);
    }

    public void recordTestMessage(long guildId, long channelId, long messageId) {
        repository.recordTestMessage(guildId, channelId, messageId);
    }

    public List<TrackingRepository.TestMessage> getTestMessages(long guildId) {
        return repository.getTestMessages(guildId);
    }

    public void clearTestMessages(long guildId) {
        repository.clearTestMessages(guildId);
    }
}
