package com.salonplatform.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/**
 * A member of a salon's staff, who signs in with a password and, as an Owner always, a second step. Its
 * password hash and authenticator secret are read only to verify a sign-in; it is never printed.
 */
@Entity
@Table(name = "identity_staff")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StaffAccount {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "email")
    private String email;

    @Column(name = "mobile")
    private String mobile;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role")
    private StaffRole role;

    @Column(name = "active")
    private boolean active;

    @Column(name = "professional_grant")
    private boolean professionalGrant;

    @Column(name = "mfa_secret")
    private String mfaSecret;

    @Version
    @Column(name = "version")
    private Long version;

    /** An active account of the current salon, which Hibernate stamps; it has no second step yet. */
    public static StaffAccount create(
            String email, String mobile, String passwordHash, StaffRole role, boolean professionalGrant, Instant at) {
        StaffAccount account = new StaffAccount();
        account.id = UUID.randomUUID();
        account.createdAt = at;
        account.updatedAt = at;
        account.email = email;
        account.mobile = mobile;
        account.passwordHash = passwordHash;
        account.role = role;
        account.active = true;
        account.professionalGrant = professionalGrant;
        return account;
    }

    public boolean hasMfa() {
        return mfaSecret != null;
    }

    /** The authenticator secret, encrypted, that every later sign-in is verified against. */
    public void enrollMfa(String encryptedSecret, Instant at) {
        mfaSecret = encryptedSecret;
        updatedAt = at;
    }

    public void changePassword(String newPasswordHash, Instant at) {
        passwordHash = newPasswordHash;
        updatedAt = at;
    }

    public void changeAccess(StaffRole newRole, boolean newProfessionalGrant, Instant at) {
        role = newRole;
        professionalGrant = newProfessionalGrant;
        updatedAt = at;
    }

    /** Removed from the salon: it keeps its identifiers and can no longer sign in. */
    public void deactivate(Instant at) {
        active = false;
        updatedAt = at;
    }
}
