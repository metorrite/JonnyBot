package com.runeclans.bot.runeclans;

import com.sun.net.httpserver.HttpServer;
import com.runeclans.bot.ops.RequestIds;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The website's request id goes on with the bot's hub calls made while answering it (T-674), and nothing is sent outside one. */
class HubRequestIdTest {
    @Test
    void theRequestIdTravelsToTheHub() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        HttpServer hub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        hub.createContext("/v1/", exchange -> {
            String id = exchange.getRequestHeaders().getFirst("X-Request-Id");
            seen.add(id == null ? "none" : id);
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        hub.start();
        try {
            RuneClansSettings settings = new RuneClansSettings(true, "http://127.0.0.1:" + hub.getAddress().getPort(), "rsh_" + "a".repeat(43), null);
            RuneClansHubClient.Transport transport = RuneClansHubClient.http(settings);
            MDC.put(RequestIds.MDC_KEY, "web-req-0001");
            try {
                assertEquals(200, transport.call("GET", "/v1/ping", null).status());
            } finally {
                MDC.remove(RequestIds.MDC_KEY);
            }
            transport.call("GET", "/v1/ping", null);
            assertEquals(List.of("web-req-0001", "none"), seen);
        } finally {
            hub.stop(0);
        }
    }
}
