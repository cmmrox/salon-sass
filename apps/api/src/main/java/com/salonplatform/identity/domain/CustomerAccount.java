package com.salonplatform.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "identity_customer")
public class CustomerAccount {
    @Id
    public UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    public UUID tenantId;

    @Column(nullable = false)
    public String mobile;

    @Column(nullable = false)
    public String name;

    public String email;
    public LocalDate birthday;

    @Column(name = "preferred_language", nullable = false)
    public String preferredLanguage = "en";

    @Column(name = "notify_sms", nullable = false)
    public boolean notifySms = true;

    @Column(name = "notify_whatsapp", nullable = false)
    public boolean notifyWhatsApp = true;

    @Column(name = "notify_email", nullable = false)
    public boolean notifyEmail = true;

    @Column(name = "marketing_consent", nullable = false)
    public boolean marketingConsent;

    @Column(nullable = false)
    public boolean active;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @Version
    public long version;

    public CustomerAccount() {}
}
