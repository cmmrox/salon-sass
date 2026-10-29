package com.salonplatform.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.service.OperatorAccounts;
import com.salonplatform.testing.Http;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Drives the operator API over real HTTP on the console hostname: synthetic operator accounts with
 * known TOTP secrets, the full password-and-MFA sign-in, and a signed rate-address assertion with a
 * fresh synthetic address per sign-in, so tests never share an address counter.
 */
public final class OperatorTestSupport {

    public static final String CONSOLE = "console.localhost";

    public record Operator(String email, String password, String totpSecret, String role) {}

    private final Http http;
    private final int port;
    private final OperatorAccounts accounts;
    private final OperatorCrypto crypto;
    private final JsonMapper json;
    private final byte[] proxyKey;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Set<Long>> usedSteps = new ConcurrentHashMap<>();

    public OperatorTestSupport(
            Http http,
            int port,
            OperatorAccounts accounts,
            OperatorCrypto crypto,
            JsonMapper json,
            Environment environment,
            Clock clock) {
        this.http = http;
        this.port = port;
        this.accounts = accounts;
        this.crypto = crypto;
        this.json = json;
        // From the bean: a random test key is drawn afresh on every Environment read.
        this.proxyKey = com.salonplatform.platform.adapter.OperatorKeys.proxyKey(crypto);
        this.clock = clock;
    }

    public String host() {
        return CONSOLE + ":" + port;
    }

    /** A new synthetic operator with an enrolled authenticator. */
    public Operator create(String role) {
        return create(role, true);
    }

    public Operator create(String role, boolean enrolled) {
        String email = "op-" + UUID.randomUUID() + "@example.test";
        String password = "synthetic " + UUID.randomUUID();
        String secret = enrolled ? crypto.newTotpSecret() : null;
        accounts.seed(List.of(new OperatorAccounts.AccountInput(email, "Synthetic " + role, role, password, secret)));
        return new Operator(email, password, secret, role);
    }

    /** A six-digit code for an unused step within the accepted window. */
    public String code(String secret) {
        long current = clock.instant().getEpochSecond() / 30;
        Set<Long> used = usedSteps.computeIfAbsent(secret, s -> ConcurrentHashMap.newKeySet());
        for (long step : new long[] {current, current - 1, current + 1}) {
            if (used.add(step)) {
                return crypto.totpCode(secret, step);
            }
        }
        throw new IllegalStateException("every step in the window is used for this secret");
    }

    public Map<String, Object> body(Http.Exchange response) {
        // JSON is read as an untyped map (Map.class); the cast states the shape the assertions then check.
        @SuppressWarnings("unchecked")
        Map<String, Object> map = json.readValue(response.body(), Map.class);
        return map;
    }

    public Http.Exchange call(String method, String path, Object body, String token) {
        return callOn(host(), method, path, body, token, Map.of());
    }

    public Http.Exchange callOn(
            String host, String method, String path, Object body, String token, Map<String, String> extra) {
        Map<String, String> headers = new HashMap<>(extra);
        if (body != null) {
            headers.put("Content-Type", "application/json");
        }
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        return http.sendOnHost(
                method,
                host,
                "/api/v1/operator" + path,
                headers,
                body == null ? null : body instanceof String text ? text : json.writeValueAsString(body));
    }

    /** A random synthetic address in 10/8. */
    public String randomAddress() {
        return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }

    /** The console BFF's signed assertion of this address, issued now. */
    public String assertion(String address) {
        String issued = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString();
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(proxyKey, "HmacSHA256"));
            String canonical = "v1\n" + CONSOLE + "\nPOST\n/api/v1/operator/sign-in\n" + address + "\n" + issued;
            return "v1;" + address + ";" + issued + ";"
                    + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Password sign-in from this address. */
    public Http.Exchange passwordSignIn(String email, String password, String address) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("email", email);
        request.put("password", password);
        request.put("deviceLabel", "JUnit console");
        return callOn(
                host(), "POST", "/sign-in", request, null, Map.of("x-salon-client-assertion", assertion(address)));
    }

    /** The whole sign-in of an enrolled operator: the tokens. */
    public Map<String, Object> signIn(Operator operator) {
        Http.Exchange password = passwordSignIn(operator.email(), operator.password(), randomAddress());
        assertThat(password.status()).withFailMessage("%s", password.body()).isEqualTo(200);
        Map<String, Object> challenge = body(password);
        assertThat(challenge.get("state")).isEqualTo("verify_mfa");
        Http.Exchange verify = call(
                "POST",
                "/mfa/verify",
                Map.of(
                        "challengeId",
                        challenge.get("challengeId"),
                        "challengeToken",
                        challenge.get("challengeToken"),
                        "code",
                        code(operator.totpSecret())),
                null);
        assertThat(verify.status()).withFailMessage("%s", verify.body()).isEqualTo(200);
        return body(verify);
    }

    public String token(Operator operator) {
        return (String) signIn(operator).get("accessToken");
    }

    /** A plan definition request with every field. */
    public static Map<String, Object> plan(String key) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("key", key);
        plan.put("name", "Synthetic fixture " + key);
        plan.put("staffSeats", 5);
        plan.put("branches", 1);
        plan.put("messageAllowance", 0);
        plan.put("customDomain", false);
        plan.put("dedicatedApp", true);
        return plan;
    }

    public static String uniqueKey(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** A complete salon request on an existing plan. */
    public static Map<String, Object> salon(String slug, String planId, String ownerEmail, String ownerPassword) {
        Map<String, Object> owner = new LinkedHashMap<>();
        owner.put("name", "Synthetic Owner");
        owner.put("email", ownerEmail);
        owner.put("password", ownerPassword);
        Map<String, Object> salon = new LinkedHashMap<>();
        salon.put("name", "Synthetic Salon " + slug);
        salon.put("slug", slug);
        salon.put("country", "LK");
        salon.put("currency", "LKR");
        salon.put("timeZone", "Asia/Colombo");
        salon.put("defaultLanguage", "si");
        salon.put("contactEmail", "contact-" + slug + "@example.test");
        salon.put("contactPhone", "077 123 4567");
        salon.put("address", "1 Synthetic Road\nColombo 07");
        salon.put("planId", planId);
        salon.put("owner", owner);
        Map<String, Object> subscription = new LinkedHashMap<>();
        subscription.put("monthlyAmount", 250000);
        subscription.put("paymentMethod", "bank_transfer");
        salon.put("subscription", subscription);
        return salon;
    }
}
