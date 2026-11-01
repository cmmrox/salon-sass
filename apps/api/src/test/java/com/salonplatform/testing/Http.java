package com.salonplatform.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Calls the running application over real HTTP, the way a client does. */
public final class Http {

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

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
        } catch (IOException ex) {
            throw new IllegalStateException(method + " " + path + " failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + path + " was interrupted", ex);
        }
    }

    /**
     * {@code GET path} with this {@code Host} header and any others, sent over a plain socket to
     * the loopback address: the hostname is what the request says, whatever it resolves to. HTTP
     * clients refuse to set {@code Host} themselves.
     */
    public Exchange getOnHost(String host, String path, Map<String, String> headers) {
        StringBuilder request = new StringBuilder("GET ")
                .append(path)
                .append(" HTTP/1.1\r\n")
                .append("Host: ")
                .append(host)
                .append("\r\n");
        headers.forEach(
                (name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        request.append("Accept: application/json\r\nConnection: close\r\n\r\n");
        return Exchange.parse(raw("127.0.0.1", request.toString()));
    }

    public Exchange getOnHost(String host, String path) {
        return getOnHost(host, path, Map.of());
    }

    /**
     * Any request with this {@code Host} header, these other headers and, when it is not null, this
     * body (sent as UTF-8 with its length).
     */
    public Exchange sendOnHost(String method, String host, String path, Map<String, String> headers, String body) {
        StringBuilder request = new StringBuilder(method)
                .append(' ')
                .append(path)
                .append(" HTTP/1.1\r\n")
                .append("Host: ")
                .append(host)
                .append("\r\n");
        headers.forEach(
                (name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        if (headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Accept"))) {
            request.append("Accept: application/json\r\n");
        }
        byte[] content = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (body != null) {
            request.append("Content-Length: ").append(content.length).append("\r\n");
        }
        request.append("Connection: close\r\n\r\n");
        String head = request.toString();
        return Exchange.parse(raw("127.0.0.1", head + new String(content, StandardCharsets.ISO_8859_1)));
    }

    /**
     * Any request with this {@code Host} header, these other headers and these body bytes as they are (a
     * multipart upload), answered as bytes, so a binary body (an image) arrives intact.
     */
    public BinaryExchange sendBytesOnHost(
            String method, String host, String path, Map<String, String> headers, byte[] body) {
        StringBuilder head = new StringBuilder(method)
                .append(' ')
                .append(path)
                .append(" HTTP/1.1\r\nHost: ")
                .append(host)
                .append("\r\n");
        headers.forEach(
                (name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
        if (body != null) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("Connection: close\r\n\r\n");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5_000);
            socket.setSoTimeout(30_000);
            // The body is written while the answer is read: a server may answer early (413) and close before
            // reading it all, and an answer already read survives the reset that follows.
            Thread writer = Thread.ofVirtual().start(() -> {
                try {
                    socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
                    if (body != null) {
                        socket.getOutputStream().write(body);
                    }
                    socket.getOutputStream().flush();
                } catch (IOException ex) {
                    // The server stopped reading; its answer is read below.
                }
            });
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try {
                for (int read; (read = socket.getInputStream().read(buffer)) != -1; ) {
                    received.write(buffer, 0, read);
                }
            } catch (IOException ex) {
                if (received.size() == 0) throw ex;
            }
            writer.join(5_000);
            return BinaryExchange.parse(received.toByteArray());
        } catch (IOException ex) {
            throw new IllegalStateException(method + " " + path + " failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(method + " " + path + " was interrupted", ex);
        }
    }

    /** A response read off the wire as bytes: status, headers (names in lower case) and the raw body. */
    public record BinaryExchange(int status, Map<String, String> headers, byte[] body) {

        static BinaryExchange parse(byte[] response) {
            int headerEnd = -1;
            for (int i = 0; i + 3 < response.length; i++) {
                if (response[i] == '\r'
                        && response[i + 1] == '\n'
                        && response[i + 2] == '\r'
                        && response[i + 3] == '\n') {
                    headerEnd = i;
                    break;
                }
            }
            if (headerEnd < 0) {
                throw new IllegalStateException("not an HTTP response");
            }
            Exchange head = Exchange.parse(new String(response, 0, headerEnd + 4, StandardCharsets.ISO_8859_1));
            byte[] body = java.util.Arrays.copyOfRange(response, headerEnd + 4, response.length);
            if ("chunked".equalsIgnoreCase(head.headers().get("transfer-encoding"))) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int at = 0;
                while (at < body.length) {
                    int lineEnd = Exchange.indexOf(body, at);
                    int size = Integer.parseInt(
                            new String(body, at, lineEnd - at, StandardCharsets.US_ASCII).split(";")[0].trim(), 16);
                    if (size == 0) {
                        break;
                    }
                    out.write(body, lineEnd + 2, size);
                    at = lineEnd + 2 + size + 2;
                }
                body = out.toByteArray();
            }
            return new BinaryExchange(head.status(), head.headers(), body);
        }

        /** The same response with its body read as UTF-8 text (a JSON or problem answer). */
        public Exchange asText() {
            return new Exchange(status, headers, new String(body, StandardCharsets.UTF_8));
        }

        public String contentType() {
            return headers.getOrDefault("content-type", "");
        }
    }

    /** {@code POST} of a JSON body on a hostname. */
    public Exchange postJsonOnHost(String host, String path, String json) {
        return sendOnHost("POST", host, path, Map.of("Content-Type", "application/json"), json);
    }

    /**
     * Sends exactly these bytes and returns everything the server answered: the status line, the
     * headers and the body. For requests no HTTP client would agree to send.
     */
    public String raw(String request) {
        return raw("localhost", request);
    }

    public String raw(String address, String request) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 5_000);
            socket.setSoTimeout(30_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("the raw request failed", ex);
        }
    }

    public static String contentType(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("");
    }

    /** A response read off the wire: status, headers (names in lower case) and the decoded body. */
    public record Exchange(int status, Map<String, String> headers, String body) {

        public static Exchange parse(String response) {
            int headerEnd = response.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                throw new IllegalStateException("not an HTTP response: " + response);
            }
            String[] lines = response.substring(0, headerEnd).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.put(
                        lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        lines[i].substring(colon + 1).trim());
            }
            String body = response.substring(headerEnd + 4);
            if ("chunked".equalsIgnoreCase(headers.get("transfer-encoding"))) {
                body = dechunk(body);
            }
            return new Exchange(status, headers, body);
        }

        private static String dechunk(String chunked) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] bytes = chunked.getBytes(StandardCharsets.UTF_8);
            int at = 0;
            while (at < bytes.length) {
                int lineEnd = indexOf(bytes, at);
                int size = Integer.parseInt(
                        new String(bytes, at, lineEnd - at, StandardCharsets.US_ASCII).split(";")[0].trim(), 16);
                if (size == 0) {
                    break;
                }
                out.write(bytes, lineEnd + 2, size);
                at = lineEnd + 2 + size + 2;
            }
            return out.toString(StandardCharsets.UTF_8);
        }

        static int indexOf(byte[] bytes, int from) {
            for (int i = from; i < bytes.length - 1; i++) {
                if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
                    return i;
                }
            }
            throw new IllegalStateException("a chunk size without its line end");
        }

        public String contentType() {
            return headers.getOrDefault("content-type", "");
        }
    }
}
