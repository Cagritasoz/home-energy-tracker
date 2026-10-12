package com.cagritasoz.user_service.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class LocalJwks {

    public static final String SIGNING_KEY_ID = "test-key-1";

    public static final RSAKey SIGNING_KEY = generate(SIGNING_KEY_ID);

    public static final RSAKey ATTACKER_KEY = generate(SIGNING_KEY_ID);

    public static final LocalJwks SHARED = new LocalJwks(SIGNING_KEY);

    private final HttpServer server;

    private final AtomicInteger requests = new AtomicInteger();

    private volatile String body;

    private volatile int failureStatus;

    public LocalJwks(RSAKey... keys) {
        publish(keys); // Set the body to the JWKSet using provided keys.
        try {
            // Port 0 -> OS chooses an available port automatically.
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        // When a request hits 127.0.0.1:<port>/jwks, call the HttpHandler.
        server.createContext("/jwks", exchange -> {
            requests.incrementAndGet(); // Count requests.
            int status = failureStatus;
            if (status != 0) {
                exchange.sendResponseHeaders(status, -1); // -1 means no body.
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
    }

    public static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    public int requestCount() {
        return requests.get();
    }

    public void resetRequestCount() {
        requests.set(0);
    }

    public void publish(RSAKey... keys) {
        List<JWK> jwks = new ArrayList<>(List.of(keys));
        body = new JWKSet(jwks).toString();
    }

    public void publishRaw(String rawBody) {
        body = rawBody;
    }

    public void failWith(int status) {
        failureStatus = status;
    }

    public void recover() {
        failureStatus = 0;
    }
}
