package com.runeclans.bot.commands.runescape;

import com.runeclans.bot.permission.AdminRoleFilter;
import com.runeclans.bot.runescape.PlayerLink;
import com.runeclans.bot.runescape.PlayerLinkService;
import com.runeclans.bot.runescape.RsnRenameRepository;
import com.runeclans.bot.runescape.RsnRenameService;
import com.runeclans.bot.runescape.VerificationRoleSyncService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who may press Confirm / Reject on a possible-rename card. */
class RsnRenameButtonsTest {
    private static final long GUILD = 10L;
    private static final long OTHER_GUILD = 20L;
    private static final long CANDIDATE = 5L;
    private static final long OWNER = 700L;
    private static final long SOMEONE_ELSE = 800L;

    private RsnRenameService renameService;
    private PlayerLinkService linkService;
    private VerificationRoleSyncService roleSync;
    private AdminRoleFilter adminFilter;
    private RsnRenameInteractionListener listener;

    @BeforeEach
    void setUp() {
        renameService = mock(RsnRenameService.class);
        linkService = mock(PlayerLinkService.class);
        roleSync = mock(VerificationRoleSyncService.class);
        adminFilter = mock(AdminRoleFilter.class);
        listener = new RsnRenameInteractionListener(renameService, linkService, roleSync, adminFilter);
        when(renameService.getCandidate(CANDIDATE)).thenReturn(new RsnRenameRepository.RenameCandidate(
                CANDIDATE, GUILD, "Old Name", "New Name", "MEDIUM", "stats", OffsetDateTime.now(), "PENDING"));
        when(renameService.confirm(anyLong(), anyLong())).thenReturn(true);
        when(renameService.reject(anyLong(), anyLong())).thenReturn(true);
        when(linkService.getLinkForRsn(GUILD, "Old Name")).thenReturn(
                new PlayerLink(1L, GUILD, OWNER, "Old Name", "MANUAL", OffsetDateTime.now(), null));
    }

    private static Guild guild(long id) {
        Guild guild = mock(Guild.class);
        when(guild.getIdLong()).thenReturn(id);
        return guild;
    }

    private static ButtonInteractionEvent press(String action, Guild guild, Member member, long userId) {
        ButtonInteractionEvent event = mock(ButtonInteractionEvent.class, Mockito.RETURNS_DEEP_STUBS);
        when(event.getComponentId()).thenReturn("rsnrename_" + action + ":" + CANDIDATE);
        when(event.getGuild()).thenReturn(guild);
        when(event.getMember()).thenReturn(member);
        when(event.getUser().getIdLong()).thenReturn(userId);
        return event;
    }

    @Test
    void aMemberWhoIsNotStaffCannotConfirmOrRejectFromTheAlertChannel() {
        Guild guild = guild(GUILD);
        Member member = mock(Member.class);
        when(adminFilter.isSupportTier(guild, member)).thenReturn(false);

        ButtonInteractionEvent confirm = press("confirm", guild, member, SOMEONE_ELSE);
        listener.onButtonInteraction(confirm);
        ButtonInteractionEvent reject = press("reject", guild, member, SOMEONE_ELSE);
        listener.onButtonInteraction(reject);

        verify(renameService, never()).confirm(anyLong(), anyLong());
        verify(renameService, never()).reject(anyLong(), anyLong());
        verify(roleSync, never()).syncLeftClan(any(), anyLong());
        verify(confirm).replyComponents(Mockito.anyList());
        verify(reject).replyComponents(Mockito.anyList());
    }

    @Test
    void staffOfAnotherServerCannotResolveThisServersRename() {
        Guild other = guild(OTHER_GUILD);
        Member staff = mock(Member.class);
        when(adminFilter.isSupportTier(other, staff)).thenReturn(true);

        listener.onButtonInteraction(press("reject", other, staff, SOMEONE_ELSE));

        verify(renameService, never()).reject(anyLong(), anyLong());
        verify(roleSync, never()).syncLeftClan(any(), anyLong());
    }

    @Test
    void staffOfTheServerCanConfirm() {
        Guild guild = guild(GUILD);
        Member staff = mock(Member.class);
        when(adminFilter.isSupportTier(guild, staff)).thenReturn(true);

        listener.onButtonInteraction(press("confirm", guild, staff, SOMEONE_ELSE));

        verify(renameService).confirm(CANDIDATE, SOMEONE_ELSE);
    }

    @Test
    void theLinkedPlayerCanAnswerFromTheirDm() {
        listener.onButtonInteraction(press("confirm", null, null, OWNER));

        verify(renameService).confirm(CANDIDATE, OWNER);
    }

    @Test
    void someoneElseCannotAnswerFromADm() {
        listener.onButtonInteraction(press("reject", null, null, SOMEONE_ELSE));

        verify(renameService, never()).reject(anyLong(), anyLong());
        verify(roleSync, never()).syncLeftClan(any(), anyLong());
    }
}
