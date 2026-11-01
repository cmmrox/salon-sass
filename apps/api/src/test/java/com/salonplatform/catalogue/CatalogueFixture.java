package com.salonplatform.catalogue;

import com.salonplatform.branding.SettingsTestSupport;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.OpenApiContract;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Requests to the F05-S01 catalogue endpoints, each answer held to the committed contract, and the bodies they
 * send. Prices are whole rupees here and minor units on the wire.
 */
final class CatalogueFixture {

    static final String CATEGORIES = "/api/v1/catalogue/categories";
    static final String SERVICES = "/api/v1/catalogue/services";
    static final String MENU = "/api/v1/catalogue/menu";
    static final String PHOTOS = "/api/v1/catalogue/photos/";
    static final String POPULAR_SETTING = "/api/v1/catalogue/popular-setting";
    static final String TAX = "/api/v1/catalogue/tax";
    static final String TAX_SETTINGS = "/api/v1/catalogue/tax/settings";
    static final String TAX_HISTORY = "/api/v1/catalogue/tax/history";
    static final String PRODUCTS = "/api/v1/catalogue/products";

    /** The prototype's ten categories, in order (decision 092). */
    static final List<String> DEFAULT_CATEGORIES = List.of(
            "Cuts & styling",
            "Colour",
            "Facials & skin",
            "Nails",
            "Brows & lashes",
            "Waxing",
            "Makeup & draping",
            "Massage & spa",
            "Bridal",
            "Consultations");

    private final SettingsTestSupport settings;
    private final Http http;
    private final int port;
    private final OpenApiContract contract = OpenApiContract.committed();

    CatalogueFixture(SettingsTestSupport settings, Http http, int port) {
        this.settings = settings;
        this.http = http;
        this.port = port;
    }

    Http.Exchange call(TestSalon salon, String method, String path, Object body, String token) {
        Http.Exchange exchange = settings.call(salon, method, path, body, token);
        contract.assertMatches(method, templateOf(path), exchange);
        return exchange;
    }

    Map<String, Object> body(Http.Exchange exchange) {
        return settings.body(exchange);
    }

    /** The categories, in order, as the API lists them. */
    @SuppressWarnings("unchecked") // JSON read as an untyped map.
    List<Map<String, Object>> categories(TestSalon salon, String token) {
        return (List<Map<String, Object>>)
                body(call(salon, "GET", CATEGORIES, null, token)).get("items");
    }

    long listVersion(TestSalon salon, String token) {
        return ((Number) body(call(salon, "GET", CATEGORIES, null, token)).get("listVersion")).longValue();
    }

    /** The id of the salon's category with this name. */
    UUID category(TestSalon salon, String token, String name) {
        return categories(salon, token).stream()
                .filter(category -> name.equals(category.get("name")))
                .map(category -> UUID.fromString((String) category.get("id")))
                .findFirst()
                .orElseThrow();
    }

    /**
     * A save of the tax settings from {@code version}: VAT with this registration number, or off for null; a service
     * charge of {@code percent}, or off for null, on products too when {@code onProducts} (F05-S03).
     */
    static Map<String, Object> tax(long version, String vatNumber, Integer percent, boolean onProducts) {
        Map<String, Object> vat = new LinkedHashMap<>();
        vat.put("registered", vatNumber != null);
        vat.put("registrationNumber", vatNumber);
        Map<String, Object> charge = new LinkedHashMap<>();
        charge.put("on", percent != null);
        charge.put("percent", percent);
        charge.put("onProducts", onProducts);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("vat", vat);
        body.put("serviceCharge", charge);
        return body;
    }

    /** A product's body to add: Rs. {@code rupees}, with its VAT choice and sold at checkout (F05-S03). */
    static Map<String, Object> product(String name, long rupees, boolean chargesVat, boolean soldAtCheckout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("priceMinor", rupees * 100);
        body.put("chargesVat", chargesVat);
        body.put("soldAtCheckout", soldAtCheckout);
        return body;
    }

    /** Creates a service and answers its body; fails the test unless it was created. */
    Map<String, Object> created(TestSalon salon, String token, Map<String, Object> service) {
        Http.Exchange exchange = call(salon, "POST", SERVICES, service, token);
        if (exchange.status() != 201) {
            throw new AssertionError("not created: " + exchange.status() + " " + exchange.body());
        }
        return body(exchange);
    }

    /** A service's body to create: Rs. {@code rupees}, {@code minutes} long, {@code buffer} after. */
    static Map<String, Object> service(UUID category, String name, long rupees, int minutes, int buffer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("categoryId", category.toString());
        body.put("description", null);
        body.put("priceMinor", rupees * 100);
        body.put("durationMinutes", minutes);
        body.put("bufferMinutes", buffer);
        body.put("bookableOnline", true);
        return body;
    }

    /**
     * A save of a service as read, with one field replaced: its own price and time, or its variants with their ids
     * (F05-S02), and its deposit, as they are.
     */
    static Map<String, Object> save(Map<String, Object> service, String field, Object value) {
        Map<?, ?> terms = (Map<?, ?>) service.get("terms");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", service.get("name"));
        body.put("categoryId", service.get("categoryId"));
        body.put("description", service.get("description"));
        List<?> variants = (List<?>) terms.get("variants");
        if (variants == null || variants.isEmpty()) {
            body.put("priceMinor", terms.get("priceMinor"));
            body.put("durationMinutes", terms.get("durationMinutes"));
        } else {
            body.put("variants", Map.of("name", terms.get("variantSetName"), "items", variantsOf(service)));
        }
        body.put("bufferMinutes", terms.get("bufferMinutes"));
        body.put("bookableOnline", service.get("bookableOnline"));
        if (service.get("deposit") != null) {
            body.put("deposit", service.get("deposit"));
        }
        body.put("version", service.get("version"));
        if (field != null) {
            body.put(field, value);
        }
        return body;
    }

    /** One variant as a request sends it: Rs. {@code rupees}, {@code minutes} long; {@code id} null for a new one. */
    static Map<String, Object> variant(Object id, String name, long rupees, int minutes) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (id != null) {
            body.put("id", id.toString());
        }
        body.put("name", name);
        body.put("priceMinor", rupees * 100);
        body.put("durationMinutes", minutes);
        return body;
    }

    /** A service's body (to create or save) with this set of variants instead of its own price and time. */
    @SafeVarargs
    static Map<String, Object> withVariants(Map<String, Object> service, String setName, Map<String, Object>... items) {
        return withVariantList(service, setName, listOf(items));
    }

    /** The items as a list, copied one by one so that the varargs array never escapes. */
    @SafeVarargs
    private static List<Map<String, Object>> listOf(Map<String, Object>... items) {
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            list.add(item);
        }
        return List.copyOf(list);
    }

    static Map<String, Object> withVariantList(
            Map<String, Object> service, String setName, List<Map<String, Object>> items) {
        Map<String, Object> body = new LinkedHashMap<>(service);
        body.remove("priceMinor");
        body.remove("durationMinutes");
        body.put("variants", Map.of("name", setName, "items", items));
        return body;
    }

    /** A save of a service as read, with this set of variants. */
    @SafeVarargs
    static Map<String, Object> saveWithVariants(
            Map<String, Object> service, String setName, Map<String, Object>... items) {
        return withVariantList(save(service, null, null), setName, listOf(items));
    }

    /** A service's variants as read, each as a request would send it back with its id. */
    static List<Map<String, Object>> variantsOf(Map<String, Object> service) {
        return maps(map(service.get("terms")).get("variants")).stream()
                .map(each -> variant(
                        each.get("id"),
                        (String) each.get("name"),
                        ((Number) each.get("priceMinor")).longValue() / 100,
                        ((Number) each.get("durationMinutes")).intValue()))
                .toList();
    }

    /** A multipart upload of a service photo, with the file name and type the client gives. */
    Http.BinaryExchange upload(TestSalon salon, Object serviceId, String fileName, byte[] content, String token) {
        String boundary = "----salon" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (content != null) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                            + fileName + "\"\r\nContent-Type: image/jpeg\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(content);
            body.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        headers.put("Accept", "application/json");
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        String path = SERVICES + "/" + serviceId + "/photo";
        Http.BinaryExchange exchange = http.sendBytesOnHost("PUT", salon.host(port), path, headers, body.toByteArray());
        contract.assertMatches("PUT", SERVICES + "/{id}/photo", exchange);
        return exchange;
    }

    /** A photo as served on a salon's hostname, as bytes. */
    Http.BinaryExchange photo(TestSalon salon, Object photoUrlOrId) {
        String path = photoUrlOrId.toString().startsWith("/") ? photoUrlOrId.toString() : PHOTOS + photoUrlOrId;
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "image/jpeg, application/problem+json");
        Http.BinaryExchange exchange = http.sendBytesOnHost("GET", salon.host(port), path, headers, null);
        contract.assertMatches("GET", PHOTOS + "{id}", exchange);
        return exchange;
    }

    /** Runs the two calls at the same moment and answers their results in order. */
    static List<Integer> together(
            java.util.concurrent.Callable<Integer> first, java.util.concurrent.Callable<Integer> second) {
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.CompletableFuture<Integer>> running = java.util.stream.Stream.of(first, second)
                .map(call -> java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                        return call.call();
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }))
                .toList();
        start.countDown();
        return running.stream()
                .map(java.util.concurrent.CompletableFuture::join)
                .toList();
    }

    /** A JSON object read as an untyped map. */
    @SuppressWarnings("unchecked") // JSON read as an untyped map.
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    /** A JSON array of objects read as untyped maps. */
    @SuppressWarnings("unchecked") // JSON read as an untyped map.
    static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    /** The contract's path for a request path: ids replaced by their template, the query dropped. */
    static String templateOf(String path) {
        String plain = path.replaceFirst("\\?.*$", "");
        return plain.replaceAll("/[0-9a-fA-F-]{36}(?=/|$)", "/{id}").replaceAll("/not-an-id(?=/|$)", "/{id}");
    }
}
