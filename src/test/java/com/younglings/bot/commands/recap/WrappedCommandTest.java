package com.younglings.bot.commands.recap;

import com.younglings.bot.config.BotConfig;
import com.younglings.bot.i18n.Text;
import com.younglings.bot.member.MemberProfileRepository;
import com.younglings.bot.member.MemberProfileRepository.RecapOwner;
import com.younglings.bot.permission.MemberAccess;
import com.younglings.bot.runescape.PlayerLink;
import com.younglings.bot.runescape.PlayerLinkRepository;
import io.github.freya022.botcommands.api.commands.application.slash.GuildSlashEvent;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import io.github.freya022.botcommands.api.localization.interaction.LocalizableInteractionHook;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /wrapped} and a member's recap being private until they've opened it ({@link com.younglings.bot.internal.RecapGate}): asking for
 * your own counts as opening it; someone else's is posted only once they opened it. Fake ids are built at run time.
 */
class WrappedCommandTest {
    private static final long GUILD = Long.parseLong("8".repeat(17) + "1");
    private static final long ASKER = Long.parseLong("8".repeat(17) + "2");
    private static final long OTHER = Long.parseLong("8".repeat(17) + "3");
    private static final String MINE = "WrapMine";
    private static final String THEIRS = "WrapTheirs";

    private final PlayerLinkRepository links = mock(PlayerLinkRepository.class);
    private final MemberProfileRepository profiles = mock(MemberProfileRepository.class);
    private final GuildSlashEvent event = mock(GuildSlashEvent.class);
    private final ReplyCallbackAction reply = mock(ReplyCallbackAction.class, RETURNS_SELF);
    private final ReplyCallbackAction deferred = mock(ReplyCallbackAction.class, RETURNS_SELF);
    private final BotConfig config = mock(BotConfig.class);
    private final MemberAccess access = mock(MemberAccess.class);
    private WrappedCommand command;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // nothing listens there: an image fetch fails at once and the command falls back to its link card
        when(config.getSiteUrl()).thenReturn("http://127.0.0.1:9");
        when(access.isMemberTier(any(), any())).thenReturn(true);
        command = new WrappedCommand(config, access, links, profiles);

        net.dv8tion.jda.api.entities.Guild guild = mock(net.dv8tion.jda.api.entities.Guild.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        net.dv8tion.jda.api.entities.Member member = mock(net.dv8tion.jda.api.entities.Member.class);
        when(member.getIdLong()).thenReturn(ASKER);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        when(event.replyComponents(anyCollection())).thenReturn(reply);
        when(event.deferReply()).thenReturn(deferred);
        LocalizableInteractionHook hook = mock(LocalizableInteractionHook.class);
        when(event.getHook()).thenReturn(hook);
        WebhookMessageEditAction<net.dv8tion.jda.api.entities.Message> edit = mock(WebhookMessageEditAction.class, RETURNS_SELF);
        when(hook.editOriginalComponents(anyCollection())).thenReturn(edit);
        when(links.getLinksForUser(GUILD, ASKER)).thenReturn(List.of(new PlayerLink(1, GUILD, ASKER, MINE, "TEST", OffsetDateTime.now(), null)));
    }

    /** All the text in the private reply's card. */
    @SuppressWarnings("unchecked")
    private String privateReplyText() {
        ArgumentCaptor<Collection<?>> sent = ArgumentCaptor.forClass(Collection.class);
        verify(event).replyComponents((Collection) sent.capture());
        verify(reply).setEphemeral(true);
        StringBuilder text = new StringBuilder();
        for (Object top : sent.getValue()) {
            for (var child : ((Container) top).getComponents()) if (child instanceof TextDisplay display) text.append(display.getContent()).append('\n');
        }
        return text.toString();
    }

    @Test
    void someoneElsesUnopenedRecapIsNotPostedAndTheAskerIsToldPrivately() {
        when(profiles.recapOwner(GUILD, THEIRS, "month:" + java.time.YearMonth.now(java.time.ZoneOffset.UTC) + ":running")).thenReturn(new RecapOwner(OTHER, false, false));
        command.onWrapped(event, null, THEIRS);
        assertTrue(privateReplyText().contains(Text.of(GUILD, "recap.that_recap_is_private_until", THEIRS)));
        verify(event, never()).deferReply();
        verify(profiles, never()).markRecapSeen(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    void anUnlinkedNameIsPrivateToo() {
        command.onWrapped(event, WrappedCommand.Period.LAST_MONTH, "Nobody");
        assertTrue(privateReplyText().contains(Text.of(GUILD, "recap.that_recap_is_private_until", "Nobody")));
        verify(event, never()).deferReply();
    }

    @Test
    void yourOwnRecapCountsAsOpenedAndIsPosted() {
        String key = "month:" + java.time.YearMonth.now(java.time.ZoneOffset.UTC).minusMonths(1) + ":final";
        when(profiles.recapOwner(GUILD, MINE, key)).thenReturn(new RecapOwner(ASKER, false, false));
        command.onWrapped(event, WrappedCommand.Period.LAST_MONTH, null);
        verify(profiles).markRecapSeen(GUILD, MINE, key, ASKER);
        verify(event).deferReply();
        verify(event, never()).replyComponents(anyCollection());
    }

    @Test
    void yourOwnRecapWithRecapsTurnedOffStaysBetweenYouAndTheBot() {
        String key = "year:" + java.time.Year.now(java.time.ZoneOffset.UTC) + ":running";
        when(profiles.recapOwner(GUILD, MINE, key)).thenReturn(new RecapOwner(ASKER, true, false));
        command.onWrapped(event, WrappedCommand.Period.YEAR, null);
        String text = privateReplyText();
        assertTrue(text.contains(Text.of(GUILD, "recap.only_you_can_see_your_recaps")), text);
        verify(profiles).markRecapSeen(GUILD, MINE, key, ASKER);
        verify(event, never()).deferReply();
    }

    @Test
    void someoneElsesOpenedRecapIsPostedAndTheClansNeedsNoCheck() {
        when(profiles.recapOwner(GUILD, THEIRS, "all")).thenReturn(new RecapOwner(OTHER, false, true));
        command.onWrapped(event, WrappedCommand.Period.ALL, THEIRS);
        verify(event).deferReply();
        verify(profiles, never()).markRecapSeen(anyLong(), anyString(), anyString(), anyLong());

        command = new WrappedCommand(config, access, links, profiles); // a fresh command: no cooldown
        command.onWrapped(event, WrappedCommand.Period.WEEK, "clan");
        verify(profiles, never()).recapOwner(anyLong(), anyString(), org.mockito.ArgumentMatchers.startsWith("week:"));
        verify(event, times(2)).deferReply();
    }
}
