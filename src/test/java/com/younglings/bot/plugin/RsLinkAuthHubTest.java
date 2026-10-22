package com.younglings.bot.plugin;

import com.younglings.bot.rsclans.RsClansHubClient;
import com.younglings.bot.rsclans.RsClansSettings;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** With the RSClans hub on, RsLinkAuth's two calls go to the hub's /v1/link routes; anything else is refused. */
class RsLinkAuthHubTest {
    @Test
    void internalPathsMapToTheHub() throws Exception {
        Map<String, String> env = Map.of("RSCLANS_HUB_ENABLED", "true", "RSCLANS_HUB_URL", "http://localhost:8090", "RSCLANS_HUB_API_KEY", "rsh_" + "k".repeat(43));
        List<String> calls = new ArrayList<>();
        RsClansHubClient.Transport fake = (method, path, body) -> {
            calls.add(method + " " + path);
            return new RsClansHubClient.Answer(200, DataObject.empty().put("ok", true));
        };
        Constructor<RsClansHubClient> make = RsClansHubClient.class.getDeclaredConstructor(RsClansSettings.class, RsClansHubClient.Transport.class);
        make.setAccessible(true);
        RsClansHubClient hub = make.newInstance(RsClansSettings.from(env::get), fake);

        RsLinkAuth.Transport t = RsLinkAuth.hubTransport(hub);
        assertEquals(200, t.call("GET", "/internal/rsnlink/v1/keys", null).status());
        assertEquals(200, t.call("POST", "/internal/rsnlink/resolve", "{}").status());
        assertNull(t.call("POST", "/internal/rsnlink/web/links", "{}"));
        assertEquals(List.of("GET /v1/link/keys", "POST /v1/link/resolve"), calls);
    }
}
