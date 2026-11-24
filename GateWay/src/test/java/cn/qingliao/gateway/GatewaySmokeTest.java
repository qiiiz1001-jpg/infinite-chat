package cn.qingliao.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** 启动真实 WebFlux 网关及本机 HTTP 上游，核对依赖隔离和路由实际生效。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.cloud.nacos.discovery.enabled=false"})
class GatewaySmokeTest {
    private static final AtomicReference<String> seenIdentity = new AtomicReference<>();
    private static final HttpServer upstream = startUpstream();
    @LocalServerPort private int port;

    private static HttpServer startUpstream() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext(
                    "/api/user/login/password",
                    exchange -> {
                        seenIdentity.set(exchange.getRequestHeaders().getFirst("X-User-Id"));
                        byte[] body = "upstream-ok".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                        exchange.close();
                    });
            server.start();
            return server;
        } catch (IOException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @DynamicPropertySource
    static void upstream(DynamicPropertyRegistry properties) {
        properties.add("USER_SERVICE_URI", () -> "lb://test-user");
        properties.add(
                "spring.cloud.discovery.client.simple.instances.test-user[0].uri",
                () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        upstream.stop(0);
    }

    @Test
    void publicLoginIsProxiedAndSpoofedIdentityIsStripped() throws Exception {
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/api/user/login/password"))
                                        .header("X-User-Id", "9999")
                                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("upstream-ok", response.body());
        assertNull(seenIdentity.get());
    }

    @Test
    void agentConsoleIsServedWithoutLoginAndUsesProtectedApis() throws Exception {
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/agent-console.html"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("/api/user/agent/"));
        assertTrue(response.body().contains("/api/ai/chat"));
    }

    @Test
    void scopedKnowledgeConsoleIsServedAndLinksToProtectedApis() throws Exception {
        var response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/knowledge.html"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("/api/ai/knowledge/"));
        assertTrue(response.body().contains("PERSONAL"));
        assertTrue(response.body().contains("SESSION"));
    }

    @Test
    void protectedBusinessRouteRejectsMissingToken() throws Exception {
        HttpResponse<String> response =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        "http://127.0.0.1:"
                                                                + port
                                                                + "/api/user/refresh/uri"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("40100"));
    }
}
