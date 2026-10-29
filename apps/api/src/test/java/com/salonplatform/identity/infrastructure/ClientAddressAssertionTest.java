package com.salonplatform.identity.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.shared.tenancy.*;
import com.salonplatform.shared.web.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressAssertionTest {
    private static final Instant NOW = Instant.parse("2026-10-13T18:00:00Z");
    private static final String PATH = "/api/v1/identity/customers/challenges";
    private final IdentitySecrets secrets = new IdentitySecrets(configured());
    private final ClientAddressAssertion assertions =
            new ClientAddressAssertion(secrets, Clock.fixed(NOW, ZoneOffset.UTC), "LOCALHOST");

    private static String key() {
        byte[] bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static MockEnvironment configured() {
        return new MockEnvironment()
                .withProperty("salon.identity.signing-key", key())
                .withProperty("salon.identity.challenge-key", key())
                .withProperty("salon.identity.encryption-key", key())
                .withProperty("salon.identity.proxy-key", key());
    }

    private String proof(String host, String method, String path, String address, String issued) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secrets.proxy(), "HmacSHA256"));
        String canonical = "v1\n" + host + "\n" + method + "\n" + path + "\n" + address + "\n" + issued;
        return "v1;"
                + address
                + ";"
                + issued
                + ";"
                + HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private void rejected(String proof, String host, String method, String path) {
        assertThatThrownBy(() -> assertions.verify(proof, host, method, path))
                .isInstanceOfSatisfying(ApplicationProblemException.class, e -> {
                    assertThat(e.status()).isEqualTo(400);
                    assertThat(e.code()).isEqualTo(ErrorCode.INVALID_CLIENT_ASSERTION);
                    assertThat(e.getMessage()).isEqualTo("invalid_client_assertion");
                });
    }

    @Test
    void literalIpNormalizationIsCrossLanguageUnambiguous() {
        assertThat(ClientAddressAssertion.canonicalIp("192.0.2.10")).isEqualTo("192.0.2.10");
        assertThat(ClientAddressAssertion.canonicalIp("2001:DB8::a"))
                .isEqualTo("2001:0db8:0000:0000:0000:0000:0000:000a");
        assertThat(ClientAddressAssertion.canonicalIp("::ffff:192.0.2.10")).isEqualTo("192.0.2.10");
        assertThat(ClientAddressAssertion.canonicalIp("0000:0000:0000:0000:0000:ffff:c000:020a"))
                .isEqualTo("192.0.2.10");
        for (String input : List.of(
                "localhost",
                "127.1",
                "0127.0.0.1",
                "0x7f.0.0.1",
                "256.0.0.1",
                "192.0.2.1:80",
                "[::1]",
                "fe80::1%en0",
                "192.0.2.1,192.0.2.2",
                " ::1",
                "2001::db8::1"))
            assertThatThrownBy(() -> ClientAddressAssertion.canonicalIp(input)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void proofBindsVersionHostMethodExactPathAndCanonicalAddress() throws Exception {
        String valid = proof("alpha.localhost", "POST", PATH, "192.0.2.10", NOW.toString());
        assertThat(assertions.verify(valid, "alpha.localhost", "POST", PATH)).isEqualTo("192.0.2.10");
        rejected(valid, "beta.localhost", "POST", PATH);
        rejected(valid, "alpha.localhost", "GET", PATH);
        rejected(valid, "alpha.localhost", "POST", PATH + "/");
        rejected(valid.replace("v1;", "v2;"), "alpha.localhost", "POST", PATH);
        rejected(valid.replace("192.0.2.10;", "192.0.2.11;"), "alpha.localhost", "POST", PATH);
        String ipv6 = ClientAddressAssertion.canonicalIp("2001:db8::1");
        assertThat(assertions.verify(
                        proof("alpha.localhost", "POST", PATH, ipv6, NOW.toString()), "alpha.localhost", "POST", PATH))
                .isEqualTo(ipv6);
    }

    @Test
    void injectedClockWindowIsInclusiveThirtySecondsAndInstantGrammarIsStrict() throws Exception {
        for (long delta : new long[] {-30, 0, 30})
            assertThat(assertions.verify(
                            proof(
                                    "alpha.localhost",
                                    "POST",
                                    PATH,
                                    "192.0.2.10",
                                    NOW.plusSeconds(delta).toString()),
                            "alpha.localhost",
                            "POST",
                            PATH))
                    .isEqualTo("192.0.2.10");
        for (Instant time :
                List.of(NOW.minusSeconds(30).minusNanos(1), NOW.plusSeconds(30).plusNanos(1)))
            rejected(
                    proof("alpha.localhost", "POST", PATH, "192.0.2.10", time.toString()),
                    "alpha.localhost",
                    "POST",
                    PATH);
        for (String issued : List.of(
                "2026-10-13T18:00:00+00:00",
                "2026-10-13 18:00:00Z",
                "2026-10-13T18:00:60Z",
                "2026-02-30T18:00:00Z",
                "2026-10-13T24:00:00Z",
                "2026-10-13T18:00:00.1234567890Z"))
            rejected(proof("alpha.localhost", "POST", PATH, "192.0.2.10", issued), "alpha.localhost", "POST", PATH);
    }

    @Test
    void boundedGrammarRejectsAmbiguousOrMalformedProofs() throws Exception {
        String valid = proof("alpha.localhost", "POST", PATH, "192.0.2.10", NOW.toString());
        for (String malformed : List.of(
                "",
                valid + ";extra",
                valid + ", " + valid,
                valid.replaceFirst(";", ";;"),
                valid.replace("v1;", " v1;"),
                valid.replace("v1;", "v1\n;"),
                "x".repeat(193),
                valid.substring(0, valid.length() - 1),
                valid.substring(0, valid.length() - 64) + "g".repeat(64)))
            rejected(malformed, "alpha.localhost", "POST", PATH);
        rejected(
                proof("alpha.localhost", "POST", PATH, "2001:db8::1", NOW.toString()), "alpha.localhost", "POST", PATH);
        rejected(
                proof("alpha.localhost", "POST", PATH, "::ffff:192.0.2.10", NOW.toString()),
                "alpha.localhost",
                "POST",
                PATH);
    }

    @Test
    void absentProofUsesOnlyServletPeerAndDuplicateProofIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRemoteAddr("192.0.2.20");
        request.addHeader("X-Forwarded-For", "192.0.2.99");
        assertThat(assertions.rateAddress(request)).isEqualTo("192.0.2.20");
        request.addHeader(
                ClientAddressAssertion.HEADER, proof("alpha.localhost", "POST", PATH, "192.0.2.10", NOW.toString()));
        CurrentTenant salon = new CurrentTenant(UUID.randomUUID(), "alpha", ZoneOffset.UTC);
        assertThat(TenantContext.callAs(salon, () -> assertions.rateAddress(request)))
                .isEqualTo("192.0.2.10");
        request.addHeader(ClientAddressAssertion.HEADER, "duplicate");
        assertThatThrownBy(() -> assertions.rateAddress(request)).isInstanceOf(ApplicationProblemException.class);
    }
}
