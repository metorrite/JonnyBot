package com.runeclans.bot.internal;

import com.runeclans.bot.clansite.SiteRoleGuard;
import com.runeclans.bot.clansite.SiteRoles;
import com.runeclans.bot.premium.Premium;
import com.runeclans.bot.premium.PremiumDatabaseInitializer;
import com.runeclans.bot.premium.PremiumGuildRepository;
import com.runeclans.bot.premium.PremiumSwitch;
import com.runeclans.bot.testsupport.LocalDatabase;
import com.runeclans.bot.upload.ImageUploadService;
import com.runeclans.bot.upload.UploadedImageDatabaseInitializer;
import com.runeclans.bot.upload.UploadedImageRepository;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A clan website's picture library over real SQL (T-408): uploads, the free (10) and premium (200) limits, nothing deleted when a plan ends,
 * delete, list, and one server's pictures never listed or deletable from another. Skipped without a local database.
 */
class SitePictureDatabaseTest {
    private static final long GUILD = 999_000_000_000_000_408L;
    private static final long OTHER = 999_000_000_000_000_409L;
    private static ConnectionSupplier supplier;

    private Premium premium;
    private ImageUploadService images;
    private UploadedImageRepository repository;
    private SiteRoleGuard roles;
    private SitePictureAdminApi api;
    private Guild guild;
    private Guild other;
    private Member editor;

    @BeforeAll
    static void connect() throws Exception {
        supplier = LocalDatabase.supplier();
    }

    @BeforeEach
    void setUp() throws Exception {
        // the free and premium limits exist only while Premium is switched on (T-422)
        PremiumSwitch.forceForTests(true);
        premium = new Premium(new PremiumGuildRepository(supplier, new PremiumDatabaseInitializer(supplier)));
        repository = new UploadedImageRepository(supplier, new UploadedImageDatabaseInitializer(supplier));
        images = new ImageUploadService(repository);
        roles = mock(SiteRoleGuard.class);
        api = new SitePictureAdminApi(images, premium, roles);
        guild = guild(GUILD);
        other = guild(OTHER);
        editor = mock(Member.class);
        when(editor.getId()).thenReturn("1");
        when(roles.allows(any(), eq(editor), eq(SiteRoles.Action.EDIT))).thenReturn(true);
        cleanUp();
    }

    private static Guild guild(long id) {
        Guild g = mock(Guild.class);
        when(g.getIdLong()).thenReturn(id);
        when(g.getId()).thenReturn(String.valueOf(id));
        return g;
    }

    @AfterEach
    void switchBack() {
        PremiumSwitch.forceForTests(null);
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : new String[]{"uploaded_image", "premium_guild", "premium_grant_log"}) {
            try (Connection c = supplier.getConnection();
                 PreparedStatement s = c.prepareStatement("DELETE FROM younglings." + table + " WHERE guild_id IN (?, ?)")) {
                s.setLong(1, GUILD);
                s.setLong(2, OTHER);
                s.executeUpdate();
            }
        }
    }

    /** A different tiny PNG each time (the filler byte differs), base64 like the website sends it. */
    private static DataObject png(int n) {
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, (byte) n, (byte) (n >> 8)};
        return DataObject.empty().put("data", Base64.getEncoder().encodeToString(bytes));
    }

    private DataObject upload(Guild g, int n) {
        return api.route(g, editor, "POST", new String[]{"site-pictures"}, () -> png(n));
    }

    private DataObject list(Guild g) {
        return api.route(g, editor, "GET", new String[]{"site-pictures"}, () -> null);
    }

    private int status(Runnable call) {
        return assertThrows(TicketAdminApi.ApiError.class, call::run).status;
    }

    @Test
    void premiumSwitchedOffGivesAFreeSiteThePremiumLimit() {
        PremiumSwitch.forceForTests(false);

        assertEquals(200, upload(guild, 1).getLong("limit"), "T-422: everything is free while PREMIUM_ENABLED is unset");
    }

    @Test
    void anUploadIsListedWithItsSizeAndServedByItsToken() {
        DataObject saved = upload(guild, 1);
        String token = saved.getString("token");

        assertTrue(token.matches("[0-9a-f]{32}"));
        assertEquals(1, saved.getInt("used"));
        assertEquals(10, saved.getLong("limit"));
        DataObject listed = list(guild).getArray("pictures").getObject(0);
        assertEquals(token, listed.getString("token"));
        assertEquals(10, listed.getLong("bytes"));
        assertEquals("image/png", listed.getString("contentType"));
        assertEquals("image/png", images.find(token).orElseThrow().contentType());
    }

    @Test
    void aFreeSiteHoldsTenPicturesAndIsThenRefusedInPlainWords() {
        for (int i = 0; i < 10; i++) upload(guild, i);

        TicketAdminApi.ApiError refused = assertThrows(TicketAdminApi.ApiError.class, () -> upload(guild, 99));
        assertEquals(409, refused.status);
        assertTrue(refused.getMessage().contains("Premium for up to 200"), refused.getMessage());
        assertEquals(10, list(guild).getInt("used"));
    }

    @Test
    void aPremiumSiteHoldsTwoHundredAndNotOneMore() {
        premium.grant(GUILD, "test", "premium", null, "test", "manual", 1L);
        for (int i = 0; i < 200; i++) upload(guild, i);

        TicketAdminApi.ApiError refused = assertThrows(TicketAdminApi.ApiError.class, () -> upload(guild, 999));
        assertEquals(409, refused.status);
        assertFalse(refused.getMessage().contains("Premium"), refused.getMessage());
        assertEquals(200, list(guild).getInt("used"));
    }

    @Test
    void picturesStayWhenThePlanEndsButNewOnesMeetTheFreeLimit() {
        premium.grant(GUILD, "test", "premium", null, "test", "manual", 1L);
        for (int i = 0; i < 12; i++) upload(guild, i);
        premium.revoke(GUILD, 1L);

        assertEquals(12, list(guild).getInt("used"));
        assertEquals(409, status(() -> upload(guild, 50)));
    }

    @Test
    void twoUploadsAtOnceNeverBothTakeTheLastPlace() throws Exception {
        for (int i = 0; i < 9; i++) upload(guild, i);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> tries = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int n = 100 + i;
                tries.add(() -> {
                    try {
                        upload(guild, n);
                        return true;
                    } catch (TicketAdminApi.ApiError e) {
                        return false;
                    }
                });
            }
            int won = 0;
            for (Future<Boolean> f : pool.invokeAll(tries)) if (f.get()) won++;
            assertEquals(1, won);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(10, list(guild).getInt("used"));
    }

    @Test
    void deleteRemovesOnlyThisServersPicture() {
        String mine = upload(guild, 1).getString("token");
        String theirs = upload(other, 2).getString("token");

        // another server neither sees nor deletes it
        assertEquals(1, list(other).getInt("used"));
        assertEquals(theirs, list(other).getArray("pictures").getObject(0).getString("token"));
        assertEquals(404, status(() -> api.route(other, editor, "DELETE", new String[]{"site-pictures", mine}, () -> null)));
        assertTrue(images.find(mine).isPresent());

        DataObject after = api.route(guild, editor, "DELETE", new String[]{"site-pictures", mine}, () -> null);
        assertTrue(after.getBoolean("removed"));
        assertEquals(0, after.getInt("used"));
        assertTrue(images.find(mine).isEmpty());
        assertTrue(images.find(theirs).isPresent());
    }

    @Test
    void theLogoAndBannerAreNotPartOfTheLibrary() {
        images.save(GUILD, "site", "logo", Base64.getDecoder().decode(png(1).getString("data")));

        assertEquals(0, list(guild).getInt("used"));
        assertEquals(404, status(() -> api.route(guild, editor, "DELETE", new String[]{"site-pictures", "logo"}, () -> null)));
    }

    @Test
    void someoneWhoMayNotEditTheSiteIsRefusedAndOtherPathsAreNotOurs() {
        Member visitor = mock(Member.class);

        assertFalse(api.admits(guild, visitor, "POST", new String[]{"site-pictures"}));
        assertEquals(403, status(() -> api.route(guild, visitor, "POST", new String[]{"site-pictures"}, () -> png(1))));
        assertNull(api.route(guild, editor, "GET", new String[]{"clan-site"}, () -> null));
        assertNull(api.route(guild, editor, "GET", new String[]{"site-pictures", "a", "b"}, () -> null));
        assertTrue(api.admits(guild, editor, "POST", new String[]{"site-pictures"}));
    }
}
