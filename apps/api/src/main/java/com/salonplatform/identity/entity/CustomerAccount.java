package com.salonplatform.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.TenantId;

/** A salon's customer, known by the mobile number a one-time code was verified for. */
@Entity
@Table(name = "identity_customer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CustomerAccount {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String mobile;

    @Column(nullable = false)
    private String name;

    private String email;

    private LocalDate birthday;

    @Column(name = "preferred_language", nullable = false)
    private String preferredLanguage;

    @Column(name = "notify_sms", nullable = false)
    private boolean notifySms;

    @Column(name = "notify_whatsapp", nullable = false)
    private boolean notifyWhatsApp;

    @Column(name = "notify_email", nullable = false)
    private boolean notifyEmail;

    @Column(name = "marketing_consent", nullable = false)
    private boolean marketingConsent;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    /**
     * A customer created when a code is first verified for their number: no name yet, English, every
     * operational channel on and no marketing consent (docs/architecture/identity-and-access.md).
     */
    public static CustomerAccount verified(String mobile, Instant at) {
        CustomerAccount account = new CustomerAccount();
        account.id = UUID.randomUUID();
        account.createdAt = at;
        account.updatedAt = at;
        account.mobile = mobile;
        account.name = "";
        account.preferredLanguage = "en";
        account.notifySms = true;
        account.notifyWhatsApp = true;
        account.notifyEmail = true;
        account.active = true;
        return account;
    }

    public void updateProfile(Profile profile, Instant at) {
        name = profile.name();
        email = profile.email();
        birthday = profile.birthday();
        preferredLanguage = profile.preferredLanguage();
        notifySms = profile.notifySms();
        notifyWhatsApp = profile.notifyWhatsApp();
        notifyEmail = profile.notifyEmail();
        marketingConsent = profile.marketingConsent();
        updatedAt = at;
    }

    /** What a customer may change about themselves; the number is the one they verified. */
    public record Profile(
            String name,
            String email,
            LocalDate birthday,
            String preferredLanguage,
            boolean notifySms,
            boolean notifyWhatsApp,
            boolean notifyEmail,
            boolean marketingConsent) {}
}
