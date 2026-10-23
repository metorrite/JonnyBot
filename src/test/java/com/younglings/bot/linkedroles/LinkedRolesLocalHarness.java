package com.younglings.bot.linkedroles;

import com.sun.net.httpserver.HttpServer;
import com.younglings.bot.internal.LinkedRolesApi;
import com.younglings.bot.runescape.RuneScapeDatabaseInitializer;
import io.github.freya022.botcommands.api.core.db.ConnectionSupplier;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.net.URI;
import java.sql.DriverManager;
import java.time.Clock;

/**
 * A local stand-in for the bot's internal API with only the Linked Roles routes, for testing the website's /linked-roles flow end to end
 * without starting the bot (no Discord gateway, no real token). Not a test: run it by hand.
 * <p>
 * Environment: {@code HARNESS_DATABASE_URL} (a throwaway local Postgres, {@code postgresql://user:@127.0.0.1:port/db}), {@code HARNESS_PORT},
 * {@code HARNESS_SECRET} (the website's {@code JONNYBOT_INTERNAL_API_SECRET}), and the {@code LINKED_ROLES_*} settings with
 * {@code LINKED_ROLES_DISCORD_API} pointing at a local mock of Discord. Every clan counts as listed.
 */
public final class LinkedRolesLocalHarness {
    private LinkedRolesLocalHarness() {}

    public static void main(String[] args) throws Exception {
        URI db = URI.create(System.getenv("HARNESS_DATABASE_URL"));
        if (!"127.0.0.1".equals(db.getHost()) && !"localhost".equals(db.getHost())) throw new IllegalArgumentException("local databases only");
        String user = db.getUserInfo().split(":", 2)[0];
        String url = "jdbc:postgresql://" + db.getHost() + ":" + db.getPort() + db.getPath();
        ConnectionSupplier supplier = Mockito.mock(ConnectionSupplier.class);
        Mockito.when(supplier.getConnection()).thenAnswer(i -> DriverManager.getConnection(url, user, ""));
        new RuneScapeDatabaseInitializer(supplier);

        LinkedRolesSettings settings = LinkedRolesSettings.from(System::getenv);
        if (!settings.enabled()) throw new IllegalStateException("Linked Roles is off: " + settings.problem());
        if (settings.apiBase().equals(LinkedRolesSettings.DEFAULT_API)) throw new IllegalStateException("point LINKED_ROLES_DISCORD_API at a local mock");
        LinkedRoles roles = LinkedRoles.forTest(settings, new LinkedRolesStore(supplier), DiscordRoleApi.http(settings), guild -> true, Clock.systemUTC());
        LinkedRolesService service = Mockito.mock(LinkedRolesService.class);
        Mockito.when(service.roles()).thenReturn(roles);
        LinkedRolesApi api = new LinkedRolesApi(service);
        String secret = System.getenv("HARNESS_SECRET");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", Integer.parseInt(System.getenv("HARNESS_PORT"))), 0);
        server.createContext("/internal/linked-roles/", exchange -> {
            if (secret == null || !secret.equals(exchange.getRequestHeaders().getFirst("X-Internal-Secret"))) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            api.handle(exchange);
        });
        server.createContext("/internal/linked-roles-harness/tick", exchange -> {
            byte[] body = ("{\"sent\":" + roles.tick() + "}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/internal/linked-roles-harness/register", exchange -> {
            byte[] body = ("{\"ok\":" + roles.registerSchema() + "}").getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        System.out.println("linked-roles harness listening on " + server.getAddress().getPort());
    }
}
