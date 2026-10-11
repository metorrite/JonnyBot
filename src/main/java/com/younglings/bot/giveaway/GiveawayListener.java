package com.younglings.bot.giveaway;

import com.younglings.bot.BotListener;
import io.github.freya022.botcommands.api.core.service.annotations.BService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;

/**
 * The Enter button on a giveaway. Pressing it enters the person; pressing it again takes them out. Only the presser sees the answer.
 * Registered with JDA by {@code Bot} because it is a {@link BotListener}.
 */
@BService
public class GiveawayListener extends ListenerAdapter implements BotListener {
    private static final Logger log = LoggerFactory.getLogger(GiveawayListener.class);

    private final GiveawayRepository repository;
    private final GiveawayService service;

    public GiveawayListener(GiveawayRepository repository, GiveawayService service) {
        this.repository = repository;
        this.service = service;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith("gw:")) return;
        try {
            Guild guild = event.getGuild();
            Member member = event.getMember();
            if (guild == null || member == null) return;
            Optional<Giveaway> found = repository.byId(Long.parseLong(id.substring(3)));
            if (found.isEmpty() || found.get().guildId() != guild.getIdLong()) {
                event.reply("That giveaway isn't running any more.").setEphemeral(true).queue();
                return;
            }
            Giveaway g = found.get();
            if (!g.open() || !g.endsAt().isAfter(Instant.now())) {
                event.reply("This giveaway has ended.").setEphemeral(true).queue();
                return;
            }
            if (g.requiredRoleId() != null && member.getRoles().stream().noneMatch(r -> r.getIdLong() == g.requiredRoleId())) {
                event.reply("This giveaway is only for members with the <@&" + g.requiredRoleId() + "> role.").setEphemeral(true).queue();
                return;
            }
            boolean in = repository.toggleEntry(g.id(), g.guildId(), member.getIdLong());
            event.reply(in ? "🎉 You're in! Press the button again to leave." : "You've left the giveaway.").setEphemeral(true).queue();
            service.refresh(guild, g);
        } catch (Exception e) {
            log.error("Unhandled exception in giveaway button '{}'", id, e);
        }
    }
}
