package com.salonplatform.identity.infrastructure;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.lifecycle.TenantDataModule;
import com.salonplatform.shared.lifecycle.TenantTableRows;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Identity's exporter and eraser for a closed salon (PLT-06). Its export leaves out every credential:
 * password hashes, MFA secrets, refresh, challenge, code and rate-key hashes, and the encrypted
 * recipient of a customer code. Erasure goes through {@code identity_erase_salon_rows}, dependants first.
 */
@Component
class IdentityTenantData implements TenantDataModule {

    private static final String HASH = "a one-way hash of a credential, never exported";

    private static final List<Table> TABLES = List.of(
            new Table("identity_refresh_history", "id", Map.of("credential_hash", HASH)),
            new Table(
                    "identity_mfa_challenge",
                    "id",
                    Map.of(
                            "credential_hash",
                            HASH,
                            "pending_secret",
                            "an authenticator secret being enrolled, never exported")),
            new Table("identity_mfa_step", "id", Map.of()),
            new Table("identity_session", "id", Map.of("refresh_hash", HASH)),
            new Table(
                    "identity_customer_challenge",
                    "id",
                    Map.of(
                            "number_hash",
                            HASH,
                            "code_hash",
                            HASH,
                            "device_hash",
                            HASH,
                            "encrypted_recipient",
                            "an encrypted delivery payload, never exported")),
            new Table("identity_rate_bucket", "id", Map.of("key_hash", HASH)),
            new Table("identity_guard", "id", Map.of()),
            new Table(
                    "identity_staff",
                    "id",
                    Map.of(
                            "password_hash",
                            "a password hash, never exported",
                            "mfa_secret",
                            "an authenticator (MFA) secret, never exported")),
            new Table("identity_customer", "id", Map.of()));

    private final JdbcTemplate jdbc;
    private final ObjectProvider<PlatformDatabase> platform;

    IdentityTenantData(JdbcTemplate jdbc, ObjectProvider<PlatformDatabase> platform) {
        this.jdbc = jdbc;
        this.platform = platform;
    }

    @Override
    public String module() {
        return "identity";
    }

    @Override
    public List<Table> tables() {
        return TABLES;
    }

    @Override
    public List<String> export(String table) {
        return TenantTableRows.export(jdbc, table(table));
    }

    @Override
    public int erase(String table, UUID salonId, Instant at, int maxRows) {
        return TenantTableRows.erase(
                platform.getObject(), "identity_erase_salon_rows", table(table).name(), salonId, at, maxRows);
    }

    private static Table table(String name) {
        return TABLES.stream()
                .filter(each -> each.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not an identity table: " + name));
    }
}
