package com.runeclans.bot.internal;

import com.runeclans.bot.discord.jobs.DiscordJob;
import com.runeclans.bot.discord.jobs.DiscordJobHandler;
import com.runeclans.bot.discord.jobs.DiscordJobs;
import com.runeclans.bot.rolepanel.RolePanel;
import com.runeclans.bot.rolepanel.RolePanelRepository;
import com.runeclans.bot.rolepanel.RolePanelService;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Discord side of the dashboard's role panels ({@link RolePanelAdminApi}), run by the outbox (T-456) so the page never waits on Discord:
 * <ul>
 *   <li>{@code rolepanel.post}: posts the panel as it is saved when the bot gets to it (or edits its message in that channel). Not idempotent.</li>
 *   <li>{@code rolepanel.attach}: puts a reactions panel on an existing message. Adding the same reactions twice is harmless.</li>
 *   <li>{@code rolepanel.takedown}: takes the message of a deleted panel down. Idempotent.</li>
 * </ul>
 */
@BService
public class RolePanelJobs implements DiscordJobHandler {
    static final String POST = "rolepanel.post";
    static final String ATTACH = "rolepanel.attach";
    static final String TAKE_DOWN = "rolepanel.takedown";

    private final RolePanelRepository repository;
    private final RolePanelService service;

    public RolePanelJobs(RolePanelRepository repository, RolePanelService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public String kind() {
        return POST;
    }

    @Override
    public List<String> kinds() {
        return List.of(POST, ATTACH, TAKE_DOWN);
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public boolean idempotent(String kind) {
        return !POST.equals(kind);
    }

    static DataObject postPayload(long panelId, long channelId) {
        return DataObject.empty().put("panelId", Long.toString(panelId)).put("channelId", Long.toString(channelId));
    }

    static DataObject attachPayload(long panelId, String link) {
        return DataObject.empty().put("panelId", Long.toString(panelId)).put("messageLink", link);
    }

    static DataObject takeDownPayload(RolePanelService.TakeDown what) {
        DataArray emojis = DataArray.empty();
        what.emojis().forEach(emojis::add);
        return DataObject.empty().put("channelId", Long.toString(what.channelId())).put("messageId", Long.toString(what.messageId())).put("attached", what.attached())
                .put("emojis", emojis);
    }

    @Override
    public DataObject run(JDA jda, DiscordJob job) {
        Guild guild = DiscordJobs.guild(jda, job);
        DataObject p = job.payload();
        try {
            return switch (job.kind()) {
                case POST -> posted(service.post(guild, panel(guild, p), p.getLong("channelId")));
                case ATTACH -> posted(service.attach(guild, panel(guild, p), p.getString("messageLink")));
                case TAKE_DOWN -> {
                    List<String> emojis = new ArrayList<>();
                    DataArray raw = p.optArray("emojis").orElseGet(DataArray::empty);
                    for (int i = 0; i < raw.length(); i++) emojis.add(raw.getString(i));
                    service.takeDown(guild, new RolePanelService.TakeDown(p.getLong("channelId"), p.getLong("messageId"), p.getBoolean("attached", false), emojis));
                    yield DataObject.empty().put("takenDown", true);
                }
                default -> throw new DiscordJobs.Refused("The bot no longer knows how to do this.");
            };
        } catch (RolePanelService.PostException e) {
            throw new DiscordJobs.Refused(e.getMessage());
        }
    }

    private RolePanel panel(Guild guild, DataObject p) {
        return repository.get(guild.getIdLong(), p.getLong("panelId")).orElseThrow(() -> new DiscordJobs.Refused("That panel was deleted before the bot got to it."));
    }

    private static DataObject posted(RolePanel panel) {
        return DataObject.empty().put("panelId", Long.toString(panel.id())).put("channelId", panel.channelId() == null ? null : Long.toString(panel.channelId()))
                .put("messageId", panel.messageId() == null ? null : Long.toString(panel.messageId()));
    }
}
