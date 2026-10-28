package com.runeclans.bot.internal;

import com.runeclans.bot.events.BingoStarterPacks;
import com.runeclans.bot.events.BingoTilePool;
import com.runeclans.bot.events.BingoTilePool.Difficulty;
import com.runeclans.bot.events.BingoTilePool.Entry;
import com.runeclans.bot.events.BingoTileRepository;
import com.runeclans.bot.events.BingoTileRepository.Stored;
import com.runeclans.bot.internal.TicketAdminApi.ApiError;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The dashboard's bingo tile pool: tidy input, no duplicates, a size limit, starter packs that never overwrite what is there. */
class BingoPoolAdminApiTest {
    private static final long GUILD = 1L;

    private BingoTileRepository repository;
    private BingoPoolAdminApi api;
    private Guild guild;
    private Member actor;

    @BeforeEach
    void setUp() {
        repository = mock(BingoTileRepository.class);
        api = new BingoPoolAdminApi(repository);
        guild = mock(Guild.class);
        actor = mock(Member.class);
        when(guild.getIdLong()).thenReturn(GUILD);
        when(repository.list(GUILD)).thenReturn(List.of());
    }

    private DataObject call(String method, String body, String... parts) {
        return api.route(guild, actor, method, parts, () -> DataObject.fromJson(body));
    }

    @Test
    void otherPathsAreNotThisFeatures() {
        assertNull(api.route(guild, actor, "GET", new String[]{"events"}, () -> null));
    }

    @Test
    void theOverviewListsPacksDifficultiesAndLimits() {
        DataObject o = call("GET", "{}", "bingo-pool");

        assertEquals(0, o.getArray("tiles").length());
        assertEquals(BingoStarterPacks.all().size(), o.getArray("packs").length());
        assertEquals(Difficulty.values().length, o.getArray("difficulties").length());
        assertEquals(BingoTilePool.MAX_TILES_PER_SERVER, o.getInt("maxTiles"));
    }

    @Test
    void aTileIsTidiedAndGetsTheDifficultysPointsByDefault() {
        when(repository.add(eq(GUILD), any(), anyString())).thenReturn(5L);

        call("POST", "{\"label\":\"  Kill   Nex \",\"difficulty\":\"hard\",\"tags\":[\" PvM \",\"pvm\"]}", "bingo-pool", "tiles");

        verify(repository).add(GUILD, new Entry("Kill Nex", Difficulty.HARD.defaultPoints(), 1, Difficulty.HARD, List.of("pvm"), true), "manual");
    }

    @Test
    void aBadTileIsRefused() {
        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", "{\"label\":\"\"}", "bingo-pool", "tiles")).status);
        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", "{\"label\":\"x\",\"points\":-5}", "bingo-pool", "tiles")).status);
        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", "{\"label\":\"x\",\"difficulty\":\"nope\"}", "bingo-pool", "tiles")).status);
        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", "{\"label\":\"x\",\"points\":1.5}", "bingo-pool", "tiles")).status);
        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", "{\"label\":\"x\",\"tags\":[\"bad!\"]}", "bingo-pool", "tiles")).status);
        verify(repository, never()).add(anyLong(), any(), anyString());
    }

    @Test
    void aSecondTileWithTheSameNameIsRefused() {
        when(repository.add(eq(GUILD), any(), anyString())).thenReturn(-1L);

        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", "{\"label\":\"Kill Nex\"}", "bingo-pool", "tiles")).status);
    }

    @Test
    void aFullPoolTakesNoMoreByHand() {
        when(repository.count(GUILD)).thenReturn(BingoTilePool.MAX_TILES_PER_SERVER);

        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", "{\"label\":\"x\"}", "bingo-pool", "tiles")).status);
    }

    @Test
    void changingATileKeepsWhatWasLeftOut() {
        Entry before = new Entry("Old", 40, 2, Difficulty.HARD, List.of("a"), true);
        when(repository.list(GUILD)).thenReturn(List.of(new Stored(9, GUILD, before, "manual")));
        when(repository.update(eq(GUILD), eq(9L), any())).thenReturn(true);

        call("PUT", "{\"enabled\":false}", "bingo-pool", "tiles", "9");

        verify(repository).update(GUILD, 9L, new Entry("Old", 40, 2, Difficulty.HARD, List.of("a"), false));
    }

    @Test
    void aMissingTileIsNotFound() {
        assertEquals(404, assertThrows(ApiError.class, () -> call("PUT", "{}", "bingo-pool", "tiles", "9")).status);
        when(repository.delete(GUILD, 9L)).thenReturn(false);
        assertEquals(404, assertThrows(ApiError.class, () -> call("POST", "{}", "bingo-pool", "tiles", "9", "delete")).status);
    }

    @Test
    void seedingLeavesTilesAlreadyThereAloneAndCountsThem() {
        when(repository.count(GUILD)).thenReturn(0);
        when(repository.add(eq(GUILD), any(), eq("combat-achievements"))).thenReturn(-1L); // every name is already in the pool

        DataObject result = call("POST", "{\"pack\":\"combat-achievements\",\"difficulties\":[\"easy\"]}", "bingo-pool", "seed");

        int wanted = BingoStarterPacks.tiles("combat-achievements", java.util.Set.of(Difficulty.EASY)).size();
        assertEquals(0, result.getInt("added"));
        assertEquals(wanted, result.getInt("skipped"));
        verify(repository, atLeastOnce()).add(eq(GUILD), any(), eq("combat-achievements"));
    }

    @Test
    void seedingRefusesAnUnknownPackOrDifficulty() {
        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", "{\"pack\":\"nope\"}", "bingo-pool", "seed")).status);
        assertEquals(400, assertThrows(ApiError.class, () -> call("POST", "{\"pack\":\"boss-drops\",\"difficulties\":[\"x\"]}", "bingo-pool", "seed")).status);
    }

    @Test
    void seedingStopsAtTheServersLimit() {
        when(repository.count(GUILD)).thenReturn(BingoTilePool.MAX_TILES_PER_SERVER - 2);
        when(repository.add(eq(GUILD), any(), anyString())).thenReturn(1L);

        DataObject result = call("POST", "{\"pack\":\"boss-drops\"}", "bingo-pool", "seed");

        assertEquals(2, result.getInt("added"));
    }

    @Test
    void clearingCanBeLimitedToAPack() {
        when(repository.deleteBySource(GUILD, "boss-drops")).thenReturn(7);

        assertEquals(7, call("POST", "{\"pack\":\"boss-drops\"}", "bingo-pool", "clear").getInt("removed"));
        call("POST", "{}", "bingo-pool", "clear");
        verify(repository).deleteBySource(GUILD, null);
    }
}
