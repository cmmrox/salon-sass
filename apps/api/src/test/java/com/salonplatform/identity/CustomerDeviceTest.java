package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.*;

import com.salonplatform.identity.adapter.CustomerDevice;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.adapter.IdentitySecrets;
import com.salonplatform.shared.tenancy.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CustomerDeviceTest {
    private static String key() {
        byte[] b = new byte[32];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.HexFormat.of().formatHex(b);
    }

    @Test
    void onlyServerIssuedSameSalonCookiesNameADevice() throws Exception {
        MockEnvironment env = new MockEnvironment()
                .withProperty("salon.identity.signing-key", key())
                .withProperty("salon.identity.challenge-key", key())
                .withProperty("salon.identity.encryption-key", key())
                .withProperty("salon.identity.proxy-key", key());
        CustomerDevice devices = new CustomerDevice(new IdentityCrypto(new IdentitySecrets(env), Clock.systemUTC()));
        CurrentTenant alpha = new CurrentTenant(UUID.randomUUID(), "alpha", ZoneOffset.UTC),
                beta = new CurrentTenant(UUID.randomUUID(), "beta", ZoneOffset.UTC);
        String cookie = TenantContext.callAs(alpha, devices::issue);
        assertThat(TenantContext.callAs(alpha, () -> devices.verifiedId(cookie)))
                .isNotBlank();
        assertThat(TenantContext.callAs(beta, () -> devices.verifiedId(cookie))).isNull();
        assertThat(TenantContext.callAs(
                        alpha, () -> devices.verifiedId(cookie.substring(0, cookie.length() - 1) + "x")))
                .isNull();
        assertThat(TenantContext.callAs(
                        alpha, () -> devices.verifiedId(UUID.randomUUID().toString())))
                .isNull();
    }
}
