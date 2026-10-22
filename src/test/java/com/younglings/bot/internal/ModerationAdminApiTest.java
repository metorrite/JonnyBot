package com.younglings.bot.internal;

import com.younglings.bot.moderation.ModAction;
import com.younglings.bot.moderation.ModCase;
import com.younglings.bot.moderation.ModCaseRepository;
import com.younglings.bot.moderation.ModSettings;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationAdminApiTest {
    private static final long GUILD = 10;
    private ModCaseRepository cases;
    private ModerationAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        cases = mock(ModCaseRepository.class);
        api = new ModerationAdminApi(cases);
        guild = mock(Guild.class);
        actor = mock(Member.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(guild.getId()).thenReturn("10");
        when(actor.getIdLong()).thenReturn(5L);
        when(actor.getId()).thenReturn("5");
        when(cases.settings(GUILD)).thenReturn(ModSettings.defaults(GUILD));
        when(cases.recent(GUILD, ModerationAdminApi.LIMIT)).thenReturn(List.of(sample()));
    }

    private static ModCase sample() {
        return new ModCase(GUILD, 3, 4, "troublemaker", 5, "Mod Mia", ModAction.TIMEOUT, "spam", 30, ModCase.COMMAND, Instant.parse("2026-10-20T10:00:00Z"), null, null, null);
    }

    private DataObject route(String method, DataObject body, String... parts) {
        return api.route(guild, actor, method, parts, () -> body);
    }

    @Test
    void theOverviewListsTheLatestCasesAndTheSafeDefaults() {
        DataObject out = route("GET", null, "moderation");
        assertTrue(out.getObject("settings").getBoolean("dmReason"));
        assertEquals(false, out.getObject("settings").getBoolean("showModerator"));
        DataObject c = out.getArray("cases").getObject(0);
        assertEquals("3", c.getString("caseNo"));
        assertEquals("TIMEOUT", c.getString("action"));
        assertEquals(30, c.getInt("durationMinutes"));
        assertNull(route("GET", null, "other"));
    }

    @Test
    void casesCanBeLookedUpByMemberModeratorAndNumber() {
        when(cases.forUser(GUILD, 4L, ModerationAdminApi.LIMIT)).thenReturn(List.of(sample()));
        when(cases.find(GUILD, 3L)).thenReturn(Optional.of(sample()));
        assertEquals(1, route("GET", null, "moderation", "user", "4").getArray("cases").length());
        assertEquals("spam", route("GET", null, "moderation", "case", "3").getObject("case").getString("reason"));
        assertThrows(TicketAdminApi.ApiError.class, () -> route("GET", null, "moderation", "case", "99"));
        assertThrows(TicketAdminApi.ApiError.class, () -> route("GET", null, "moderation", "user", "not-a-number"));
    }

    @Test
    void settingsAreValidatedAndSaved() {
        Role role = mock(Role.class);
        when(guild.getRoleById(77L)).thenReturn(role);
        route("PUT", DataObject.fromJson("{\"dmReason\":false,\"showModerator\":true,\"appealUrl\":\"example.com/appeal\",\"protectedRoleIds\":[\"77\",\"77\"]}"), "moderation", "settings");

        ArgumentCaptor<ModSettings> saved = ArgumentCaptor.forClass(ModSettings.class);
        verify(cases).saveSettings(saved.capture());
        assertEquals(false, saved.getValue().dmReason());
        assertEquals(true, saved.getValue().showModerator());
        assertEquals("https://example.com/appeal", saved.getValue().appealUrl());
        assertEquals(List.of(77L), saved.getValue().protectedRoleIds());
    }

    @Test
    void aBadAppealLinkOrAMissingRoleRefusesTheWholeSave() {
        var bad = assertThrows(TicketAdminApi.ApiError.class,
                () -> route("PUT", DataObject.fromJson("{\"appealUrl\":\"javascript:alert(1)\"}"), "moderation", "settings"));
        assertTrue(bad.getMessage().contains("can't be saved"));
        assertThrows(TicketAdminApi.ApiError.class,
                () -> route("PUT", DataObject.fromJson("{\"protectedRoleIds\":[\"12345\"]}"), "moderation", "settings"));
        verify(cases, never()).saveSettings(any());
    }

    @Test
    void editingAReasonNeedsATextAndAnExistingCase() {
        when(cases.editReason(GUILD, 3L, "better", 5L)).thenReturn(true);
        when(cases.find(GUILD, 3L)).thenReturn(Optional.of(sample()));
        assertEquals("3", route("PUT", DataObject.fromJson("{\"reason\":\" better \"}"), "moderation", "case", "3").getObject("case").getString("caseNo"));

        assertThrows(TicketAdminApi.ApiError.class, () -> route("PUT", DataObject.fromJson("{\"reason\":\"  \"}"), "moderation", "case", "3"));
        assertThrows(TicketAdminApi.ApiError.class, () -> route("PUT", DataObject.fromJson("{\"reason\":\"x\"}"), "moderation", "case", "99"));
    }
}
