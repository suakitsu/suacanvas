package com.openimage.security.turnstile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnstileServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingTokenWhenEnabled() {
        TurnstileService service = service(properties("http://127.0.0.1:1/verify", true));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.verify("", "127.0.0.1"));

        assertEquals(TurnstileService.FAILURE_MESSAGE, error.getMessage());
    }

    @Test
    void rejectsCloudflareFailureResponse() throws Exception {
        TurnstileProperties properties = properties(startServer(200, "{\"success\":false}", new AtomicReference<>()), true);
        TurnstileService service = service(properties);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.verify("bad-token", "127.0.0.1"));

        assertEquals(TurnstileService.FAILURE_MESSAGE, error.getMessage());
    }

    @Test
    void rejectsCloudflareCallFailure() throws Exception {
        TurnstileProperties properties = properties(startServer(502, "{\"success\":false}", new AtomicReference<>()), true);
        TurnstileService service = service(properties);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.verify("token", "127.0.0.1"));

        assertEquals(TurnstileService.FAILURE_MESSAGE, error.getMessage());
    }

    @Test
    void acceptsSuccessfulVerificationAndSendsExpectedFormFields() throws Exception {
        AtomicReference<String> body = new AtomicReference<>("");
        TurnstileProperties properties = properties(startServer(200, "{\"success\":true}", body), true);
        TurnstileService service = service(properties);

        assertDoesNotThrow(() -> service.verify("token value", "203.0.113.10"));

        assertTrue(body.get().contains("secret=test-secret"));
        assertTrue(body.get().contains("response=token+value"));
        assertTrue(body.get().contains("remoteip=203.0.113.10"));
    }

    @Test
    void skipsVerificationWhenDisabled() {
        TurnstileService service = service(properties("http://127.0.0.1:1/verify", false));

        assertDoesNotThrow(() -> service.verify("", ""));
    }

    @Test
    void skipsBillingVerificationWhenBillingDisabled() {
        TurnstileProperties properties = properties("http://127.0.0.1:1/verify", true);
        properties.setBillingEnabled(false);
        TurnstileService service = service(properties);

        assertDoesNotThrow(() -> service.verifyBilling("", null));
    }

    private TurnstileService service(TurnstileProperties properties) {
        return new TurnstileService(properties, json, HttpClient.newHttpClient());
    }

    private TurnstileProperties properties(String verifyUrl, boolean enabled) {
        TurnstileProperties properties = new TurnstileProperties();
        properties.setEnabled(enabled);
        properties.setSecretKey("test-secret");
        properties.setVerifyUrl(verifyUrl);
        properties.setTimeoutSeconds(2);
        return properties;
    }

    private String startServer(int status, String responseBody, AtomicReference<String> capturedBody) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/verify", exchange -> respond(exchange, status, responseBody, capturedBody));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/verify";
    }

    private void respond(HttpExchange exchange, int status, String responseBody, AtomicReference<String> capturedBody) throws java.io.IOException {
        capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
