package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.infrastructure.*;
import com.salonplatform.testing.Http;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Validated proxy address drives existing durable controls; invalid proof has no side effects. */
class ClientAddressAssertionIntegrationTest extends CustomerHttpFixture {
    private static final String CUSTOMER = "/api/v1/identity/customers/challenges",
            STAFF = "/api/v1/identity/staff/sign-in";

    @BeforeEach
    void resetStaff() {
        for (String salon : List.of("alpha", "beta", "gamma"))
            status(call(salon, "DELETE", "/local/identity", null, null, null), 204);
    }

    private String proof(String salon, String method, String path, String address, Instant issued) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(app.bean(IdentitySecrets.class).proxy(), "HmacSHA256"));
        String canonical = "v1\n" + salon + ".localhost\n" + method + "\n" + path + "\n" + address + "\n" + issued;
        return "v1;"
                + address
                + ";"
                + issued
                + ";"
                + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private Http.Exchange post(String salon, String path, Object input, String assertion) {
        Map<String, String> headers =
                new HashMap<>(Map.of("Content-Type", "application/json", "X-Forwarded-For", "192.0.2.200"));
        if (assertion != null) headers.put(ClientAddressAssertion.HEADER, assertion);
        return app.http()
                .sendOnHost("POST", salon + ".localhost:" + app.port(), path, headers, json.writeValueAsString(input));
    }

    private Map<String, Object> customer(int number) {
        return Map.of("mobile", mobile(number), "channel", "sms", "deviceLabel", "Proxy device");
    }

    private Map<String, Object> staff(int number) {
        return Map.of(
                "identifier",
                "unknown" + number + "@synthetic.example",
                "password",
                UUID.randomUUID() + " synthetic",
                "deviceLabel",
                "Proxy device");
    }

    private long count(String table) {
        return database.asOwner().queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void invalidProofRejectsBothMutationsWithoutCountersChallengesCookiesOutboxOrBusinessAudit() throws Exception {
        Instant now = app.bean(Clock.class).instant();
        for (String path : List.of(CUSTOMER, STAFF)) {
            long rates = count("identity_rate_bucket"),
                    messages = count("outbox_message"),
                    audits = count("audit_entry"),
                    challenges = count("identity_customer_challenge");
            for (String assertion : List.of(
                    "",
                    "x".repeat(193),
                    proof("beta", "POST", path, "192.0.2.10", now),
                    proof("alpha", "GET", path, "192.0.2.10", now),
                    proof("alpha", "POST", path + "/", "192.0.2.10", now),
                    proof("alpha", "POST", path, "192.0.2.10", now.minusSeconds(31)))) {
                var r = post("alpha", path, path.equals(CUSTOMER) ? customer(400) : staff(400), assertion);
                status(r, 400);
                assertThat(body(r)).containsEntry("code", "invalid_client_assertion");
                assertThat(r.body()).doesNotContain(assertion.isEmpty() ? "192.0.2.10" : assertion, "192.0.2.10");
                assertThat(r.headers()).doesNotContainKey("set-cookie");
            }
            assertThat(count("identity_rate_bucket")).isEqualTo(rates);
            assertThat(count("outbox_message")).isEqualTo(messages);
            assertThat(count("audit_entry")).isEqualTo(audits);
            assertThat(count("identity_customer_challenge")).isEqualTo(challenges);
        }
    }

    @Test
    void authenticatedBrowserAddressGetsItsOwnExistingCustomerCeilingAndOtherSalonIsIndependent() throws Exception {
        for (int i = 0; i < 21; i++) {
            String assertion = proof(
                    "alpha",
                    "POST",
                    CUSTOMER,
                    "192.0.2.10",
                    app.bean(Clock.class).instant());
            status(post("alpha", CUSTOMER, customer(410 + i), assertion), i < 20 ? 200 : 429);
        }
        status(
                post(
                        "alpha",
                        CUSTOMER,
                        customer(440),
                        proof(
                                "alpha",
                                "POST",
                                CUSTOMER,
                                "192.0.2.11",
                                app.bean(Clock.class).instant())),
                200);
        status(
                post(
                        "beta",
                        CUSTOMER,
                        customer(440),
                        proof(
                                "beta",
                                "POST",
                                CUSTOMER,
                                "192.0.2.10",
                                app.bean(Clock.class).instant())),
                200);
        // A direct/native caller still uses its real servlet peer, rather than an asserted
        // browser's address.
        status(post("alpha", CUSTOMER, customer(441), null), 200);
        assertThat(count("identity_customer_challenge")).isEqualTo(23);
    }

    @Test
    void onlyValidatedProofChangesStaffAddressAccountingAndAbsenceIgnoresForwardingHeaders() throws Exception {
        status(
                post(
                        "alpha",
                        STAFF,
                        staff(450),
                        proof(
                                "alpha",
                                "POST",
                                STAFF,
                                "192.0.2.50",
                                app.bean(Clock.class).instant())),
                401);
        status(post("alpha", STAFF, staff(451), null), 401);
        status(post("alpha", STAFF, staff(452), null), 401);
        IdentityCrypto crypto = app.bean(IdentityCrypto.class);
        var rows = database.asOwner()
                .queryForList(
                        "SELECT key_hash,attempts FROM identity_rate_bucket WHERE" + " dimension='staff-address'");
        assertThat(rows).hasSize(2);
        assertThat(rows)
                .anySatisfy(row -> assertThat(row)
                        .containsEntry("key_hash", crypto.hash("rate-staff-address", "192.0.2.50"))
                        .containsEntry("attempts", 1));
        assertThat(rows)
                .noneSatisfy(row ->
                        assertThat(row).containsEntry("key_hash", crypto.hash("rate-staff-address", "192.0.2.200")));
        assertThat(rows).anySatisfy(row -> assertThat(row.get("attempts")).isEqualTo(2));
    }

    @Test
    void assertionUsesInjectedClockAndDoesNotAuthorizeProtectedOrUnrelatedRoutes() throws Exception {
        status(call(null, "PUT", "/local/clock", Map.of("instant", "2026-10-13T22:00:00Z"), null, null), 200);
        String valid = proof(
                "alpha",
                "POST",
                CUSTOMER,
                "2001:0db8:0000:0000:0000:0000:0000:0001",
                app.bean(Clock.class).instant());
        status(post("alpha", CUSTOMER, customer(460), valid), 200);
        var protectedResponse = app.http()
                .sendOnHost(
                        "GET",
                        "alpha.localhost:" + app.port(),
                        "/api/v1/identity/me",
                        Map.of(ClientAddressAssertion.HEADER, valid),
                        null);
        status(protectedResponse, 401);
        status(post("beta", CUSTOMER, customer(461), valid), 400);
        status(
                post("alpha", CUSTOMER, customer(462), proof("alpha", "POST", CUSTOMER, "192.0.2.10", Instant.now())),
                400);
    }
}
