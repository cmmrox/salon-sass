package com.salonplatform.identity.adapter;

import com.salonplatform.shared.tenancy.TenantContext;
import org.springframework.stereotype.Component;

/**
 * Authenticated opaque cookie: only server-issued device IDs are accepted. Never a tenant selector.
 */
@Component
public class CustomerDevice {
    private final IdentityCrypto crypto;

    public CustomerDevice(IdentityCrypto crypto) {
        this.crypto = crypto;
    }

    public String issue() {
        String id = crypto.randomToken();
        return id + "." + crypto.hash("device:" + TenantContext.require().id(), id);
    }

    public String verifiedId(String cookie) {
        if (cookie == null || cookie.length() > 128) return null;
        String[] p = cookie.split("\\.");
        if (p.length != 2 || !p[0].matches("[A-Za-z0-9_-]{43}")) return null;
        return crypto.equalHash(
                        p[1], crypto.hash("device:" + TenantContext.require().id(), p[0]))
                ? p[0]
                : null;
    }
}
