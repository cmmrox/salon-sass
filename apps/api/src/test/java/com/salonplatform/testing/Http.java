package com.salonplatform.testing;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Calls the running application over real HTTP, the way a client does. */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final int port;

    public Http(int port) {
        this.port = port;
    }

    public HttpResponse<String> get(String path) {
        return send("GET", path);
    }

    public HttpResponse<String> send(String method, String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException ex) {
            throw new IllegalStateException(method + " " + path + " failed", ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + path + " was interrupted", ex);
        }
    }

    public static String contentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }
}
