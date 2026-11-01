package com.salonplatform.branding;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.StaffRole;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.platform.OperatorTestSupport;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.LongStream;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The people who reach a salon's settings, over real HTTP and PostgreSQL: staff of every role, written the
 * way identity's fixtures write them and signed in through the real sign-in (an Owner with a known
 * authenticator), and an operator's support session entered through the real entry code.
 */
final class SettingsTestSupport {

    private final Http http;
    private final int port;
    private final TestSalons salons;
    private final IdentityCrypto identity;
    private final JsonMapper json;
    private final PlatformTransactionManager transactions;
    private final EntityManager entities;
    private final Clock clock;
    private final OperatorTestSupport console;
    private final Map<UUID, String> totp = new HashMap<>();
    private final Map<UUID, Set<Long>> usedSteps = new HashMap<>();
    private final String password = "synthetic staff " + UUID.randomUUID();

    SettingsTestSupport(
            Http http,
            int port,
            TestSalons salons,
            IdentityCrypto identity,
            JsonMapper json,
            PlatformTransactionManager transactions,
            EntityManager entities,
            Clock clock,
            OperatorTestSupport console) {
        this.http = http;
        this.port = port;
        this.salons = salons;
        this.identity = identity;
        this.json = json;
        this.transactions = transactions;
        this.entities = entities;
        this.clock = clock;
        this.console = console;
    }

    /** A staff member of the salon in this role, signed in: the account and its access token. */
    record Staff(StaffAccount account, String token) {}

    Staff signedIn(TestSalon salon, StaffRole role) {
        StaffAccount account;
        try {
            account = staff(salon, role);
        } catch (Exception ex) {
            throw new IllegalStateException("the staff fixture could not be written", ex);
        }
        return new Staff(account, token(salon, account));
    }

    private StaffAccount staff(TestSalon salon, StaffRole role) throws Exception {
        String email = role.name().toLowerCase(Locale.ROOT) + "-"
                + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
        return TenantContext.callAs(
                salons.asCurrent(salon),
                () -> new TransactionTemplate(transactions).execute(status -> {
                    StaffAccount account = StaffAccount.create(
                            email, null, identity.encodePassword(password), role, false, clock.instant());
                    if (role == StaffRole.OWNER) {
                        String secret = identity.newTotpSecret();
                        totp.put(account.getId(), secret);
                        account.enrollMfa(
                                identity.encrypt("totp:" + salon.id() + ":" + account.getId(), secret),
                                account.getCreatedAt());
                    }
                    entities.persist(account);
                    return account;
                }));
    }

    // JSON is read as an untyped map; the cast states the shape the sign-in answers.
    @SuppressWarnings("unchecked")
    private String token(TestSalon salon, StaffAccount account) {
        Http.Exchange signIn = call(
                salon,
                "POST",
                "/api/v1/identity/staff/sign-in",
                Map.of("identifier", account.getEmail(), "password", password, "deviceLabel", "JUnit settings"),
                null);
        assertThat(signIn.status()).withFailMessage("%s", signIn.body()).isEqualTo(200);
        Map<String, Object> result = body(signIn);
        if (result.get("credentials") != null) {
            return (String) ((Map<String, Object>) result.get("credentials")).get("accessToken");
        }
        long current = clock.instant().getEpochSecond() / 30;
        Set<Long> used = usedSteps.computeIfAbsent(account.getId(), id -> new HashSet<>());
        long step = LongStream.of(current, current - 1, current + 1)
                .filter(used::add)
                .findFirst()
                .orElseThrow();
        Http.Exchange verify = call(
                salon,
                "POST",
                "/api/v1/identity/staff/mfa/verify",
                Map.of(
                        "challengeId", result.get("challengeId"),
                        "challengeToken", result.get("challengeToken"),
                        "code", identity.totpCode(totp.get(account.getId()), step)),
                null);
        assertThat(verify.status()).withFailMessage("%s", verify.body()).isEqualTo(200);
        return (String) body(verify).get("accessToken");
    }

    /** An operator's support session in the salon, entered with its single-use code: its access token. */
    String supportSession(TestSalon salon) {
        String operator = console.token(console.create("OPERATOR_SUPPORT"));
        Http.Exchange started = console.call(
                "POST",
                "/salons/" + salon.id() + "/support-sessions",
                Map.of("reason", "Owner asked for help with the salon profile", "durationMinutes", 30),
                operator);
        assertThat(started.status()).withFailMessage("%s", started.body()).isEqualTo(201);
        Http.Exchange entered = call(
                salon,
                "POST",
                "/api/v1/support/entry",
                Map.of("code", body(started).get("entryCode")),
                null);
        assertThat(entered.status()).withFailMessage("%s", entered.body()).isEqualTo(200);
        return (String) body(entered).get("accessToken");
    }

    Http.Exchange call(TestSalon salon, String method, String path, Object request, String token) {
        Map<String, String> headers = new HashMap<>();
        if (request != null) {
            headers.put("Content-Type", "application/json");
        }
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        return http.sendOnHost(
                method,
                salon.host(port),
                path,
                headers,
                request == null ? null : request instanceof String text ? text : json.writeValueAsString(request));
    }

    /** A multipart upload of a brand image, sent as its bytes, with the file name and type the client gives. */
    Http.BinaryExchange upload(
            TestSalon salon, String kind, String fileName, String declaredType, byte[] content, String token) {
        String boundary = "----salon" + UUID.randomUUID().toString().replace("-", "");
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        java.nio.charset.Charset ascii = java.nio.charset.StandardCharsets.US_ASCII;
        if (kind != null) {
            body.writeBytes(
                    ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"kind\"\r\n\r\n" + kind + "\r\n")
                            .getBytes(ascii));
        }
        if (content != null) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                            + fileName + "\"\r\nContent-Type: " + declaredType + "\r\n\r\n")
                    .getBytes(ascii));
            body.writeBytes(content);
            body.writeBytes("\r\n".getBytes(ascii));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(ascii));
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        headers.put("Accept", "application/json");
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        return http.sendBytesOnHost(
                "POST", salon.host(port), "/api/v1/salon/brand/images", headers, body.toByteArray());
    }

    /** An image as served on a salon's hostname, as bytes. */
    Http.BinaryExchange image(TestSalon salon, Object id, String token) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "image/png, image/jpeg, application/problem+json");
        if (token != null) {
            headers.put("Authorization", "Bearer " + token);
        }
        return http.sendBytesOnHost("GET", salon.host(port), "/api/v1/salon/brand/images/" + id, headers, null);
    }

    // JSON is read as an untyped map (Map.class).
    @SuppressWarnings("unchecked")
    Map<String, Object> body(Http.Exchange exchange) {
        return json.readValue(exchange.body(), Map.class);
    }
}
