package com.younglings.bot.internal;

import com.younglings.bot.devtools.DevSnapshotRepository;
import com.younglings.bot.devtools.DevSnapshotService;
import com.younglings.bot.notice.BotOwners;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevSnapshotAdminApiTest {
    private DevSnapshotService service;
    private DevSnapshotRepository repository;
    private BotOwners owners;
    private DevSnapshotAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        service = mock(DevSnapshotService.class);
        repository = mock(DevSnapshotRepository.class);
        owners = mock(BotOwners.class);
        api = new DevSnapshotAdminApi(service, repository, owners);
        guild = mock(Guild.class);
        when(guild.getJDA()).thenReturn(mock(JDA.class));
        actor = mock(Member.class);
        when(actor.getIdLong()).thenReturn(7L);
    }

    private static final List<String[]> PATHS = List.of(
            new String[]{"dev", "snapshot"}, new String[]{"dev", "snapshots"}, new String[]{"dev", "snapshots", "snap-20261018-120000-abcd"});

    @Test
    void nonOwnersGetForbiddenOnEveryPathAndNothingIsTouched() {
        when(owners.isOwner(any(), anyLong())).thenReturn(false);
        for (String[] path : PATHS) {
            for (String method : List.of("GET", "POST", "DELETE")) {
                ApiErrorAssert.status(403, () -> api.route(guild, actor, method, path, DataObject::empty));
            }
        }
        verifyNoInteractions(service, repository);
    }

    @Test
    void otherPathsAreNotThisExtensions() {
        assertNull(api.route(guild, actor, "GET", new String[]{"premium-guilds"}, DataObject::empty));
        assertNull(api.route(guild, actor, "GET", new String[]{"dev"}, DataObject::empty));
        assertNull(api.route(guild, actor, "GET", new String[]{"dev", "other"}, DataObject::empty));
        verify(owners, never()).isOwner(any(), anyLong());
    }

    @Test
    void anOddIdIsRefusedBeforeTheDatabaseIsAsked() {
        when(owners.isOwner(any(), anyLong())).thenReturn(true);
        ApiErrorAssert.status(400, () -> api.route(guild, actor, "GET", new String[]{"dev", "snapshots", "x'; DROP TABLE"}, DataObject::empty));
        verify(repository, never()).find(anyString());
    }

    @Test
    void anOwnerCanListAndAMissingSnapshotIs404() {
        when(owners.isOwner(any(), anyLong())).thenReturn(true);
        when(repository.list()).thenReturn(List.of());
        assertEquals(DevSnapshotRepository.KEEP_DAYS, api.route(guild, actor, "GET", new String[]{"dev", "snapshots"}, DataObject::empty).getInt("keepDays"));
        when(repository.find("snap-20261018-120000-abcd")).thenReturn(java.util.Optional.empty());
        ApiErrorAssert.status(404, () -> api.route(guild, actor, "GET", new String[]{"dev", "snapshots", "snap-20261018-120000-abcd"}, DataObject::empty));
    }

    /** ApiError is package-private to this package; this reads its status without a reflection hack in each test. */
    private static final class ApiErrorAssert {
        static void status(int expected, Runnable call) {
            TicketAdminApi.ApiError error = assertThrows(TicketAdminApi.ApiError.class, call::run);
            assertEquals(expected, error.status);
        }
    }
}
