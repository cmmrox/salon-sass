package com.salonplatform.identity.application;

import com.salonplatform.identity.CustomerDataErased;
import com.salonplatform.identity.domain.CustomerAccount;
import com.salonplatform.identity.domain.Identifiers;
import com.salonplatform.identity.infrastructure.IdentityCrypto;
import com.salonplatform.shared.api.*;
import com.salonplatform.shared.audit.*;
import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.time.SalonTime;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Own profile and data rights; all privacy effects share the identity security transaction. */
@Service
public class CustomerProfile {
    @PersistenceContext
    private EntityManager em;

    private final IdentityWork work;
    private final SessionAccess sessions;
    private final IdentityCrypto crypto;
    private final JsonMapper json;
    private final Clock clock;
    private final AuditTrail audit;
    private final Outbox outbox;
    private final ApplicationEventPublisher events;

    public CustomerProfile(
            IdentityWork work,
            SessionAccess sessions,
            IdentityCrypto crypto,
            JsonMapper json,
            Clock clock,
            AuditTrail audit,
            Outbox outbox,
            ApplicationEventPublisher events) {
        this.work = work;
        this.sessions = sessions;
        this.crypto = crypto;
        this.json = json;
        this.clock = clock;
        this.audit = audit;
        this.outbox = outbox;
        this.events = events;
    }

    @io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"sms", "whatsapp", "email"})
    public record Preferences(boolean sms, boolean whatsapp, boolean email) {}

    @io.swagger.v3.oas.annotations.media.Schema(
            requiredProperties = {
                "id",
                "version",
                "name",
                "mobile",
                "email",
                "birthday",
                "preferredLanguage",
                "notificationPreferences",
                "marketingConsent"
            })
    public record Profile(
            UUID id,
            long version,
            String name,
            String mobile,

            @io.swagger.v3.oas.annotations.media.Schema(types = {"string", "null"})
            String email,

            @io.swagger.v3.oas.annotations.media.Schema(
                    types = {"string", "null"},
                    format = "date")
            LocalDate birthday,

            String preferredLanguage,
            Preferences notificationPreferences,
            boolean marketingConsent) {}

    @io.swagger.v3.oas.annotations.media.Schema(requiredProperties = {"generatedAt", "profile", "devices"})
    public record Export(Instant generatedAt, Profile profile, List<StaffAccess.SessionView> devices) {}

    public record Change(
            long version,
            String name,
            String email,
            LocalDate birthday,
            String preferredLanguage,
            Preferences preferences,
            boolean marketingConsent) {}

    public Profile read() {
        return work.run(() -> view(sessions.customerAuth().customer()));
    }

    public Profile update(Change change) {
        return work.run(() -> {
            SessionAccess.Auth auth = sessions.customerAuth();
            CustomerAccount a = auth.customer();
            String name = change.name() == null ? null : change.name().trim();
            if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 120)
                throw field("name", "must contain 1 to 120 characters");
            String email;
            try {
                email = Identifiers.email(change.email());
            } catch (IllegalArgumentException ex) {
                throw field("email", "must be a valid email address");
            }
            if (change.birthday() != null
                    && change.birthday()
                            .isAfter(SalonTime.toLocal(
                                            clock.instant(),
                                            TenantContext.require().timeZone())
                                    .toLocalDate())) throw field("birthday", "must not be in the future");
            if (!Set.of("en", "si", "ta").contains(change.preferredLanguage()))
                throw field("preferredLanguage", "must be en, si or ta");
            if (a.version != change.version()) throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
            List<String> changed = new ArrayList<>();
            if (!Objects.equals(a.name, name)) changed.add("name");
            if (!Objects.equals(a.email, email)) changed.add("email");
            if (!Objects.equals(a.birthday, change.birthday())) changed.add("birthday");
            if (!a.preferredLanguage.equals(change.preferredLanguage())) changed.add("preferredLanguage");
            Preferences before = preferences(a);
            if (!before.equals(change.preferences())) changed.add("notificationPreferences");
            if (a.marketingConsent != change.marketingConsent()) changed.add("marketingConsent");
            long previous = a.version;
            a.name = name;
            a.email = email;
            a.birthday = change.birthday();
            a.preferredLanguage = change.preferredLanguage();
            a.notifySms = change.preferences().sms();
            a.notifyWhatsApp = change.preferences().whatsapp();
            a.notifyEmail = change.preferences().email();
            a.marketingConsent = change.marketingConsent();
            a.updatedAt = clock.instant();
            em.flush();
            audit.record(new AuditAction(
                    auth.actor(),
                    "identity.profile_updated",
                    "customer",
                    a.id.toString(),
                    null,
                    Map.of("version", previous),
                    Map.of("version", a.version, "changedFields", changed)));
            return view(a);
        });
    }

    public Export export() {
        return work.run(() -> {
            SessionAccess.Auth auth = sessions.customerAuth();
            List<StaffAccess.SessionView> devices = new ArrayList<>();
            int page = 0;
            while (true) {
                var part = sessions.sessions(page++, 100);
                devices.addAll(part.items());
                if (devices.size() >= part.total()) break;
            }
            audit.record(new AuditAction(
                    auth.actor(),
                    "identity.data_exported",
                    "customer",
                    auth.ownerId().toString(),
                    null,
                    null,
                    null));
            return new Export(clock.instant(), view(auth.customer()), List.copyOf(devices));
        });
    }

    public void delete(boolean confirm) {
        work.run(() -> {
            SessionAccess.Auth auth = sessions.customerAuth();
            if (!confirm)
                throw new ApplicationProblemException(
                        400, ErrorCode.CONFIRMATION_REQUIRED, List.of(new FieldProblem("confirm", "must be true")));
            CustomerAccount a = auth.customer();
            String numberHash = crypto.hash("customer-number", a.mobile);
            Set<UUID> erased = new HashSet<>(outbox.eraseMatching(
                    Set.of("sms", "whatsapp"), payload -> matches(payload, numberHash), clock.instant()));
            erased.addAll(
                    em.createQuery("select c.id from CustomerChallenge c where" + " c.numberHash=:number", UUID.class)
                            .setParameter("number", numberHash)
                            .getResultList());
            em.createQuery("delete from CustomerChallenge c where c.numberHash=:number")
                    .setParameter("number", numberHash)
                    .executeUpdate();
            em.createQuery("delete from RefreshHistory h where h.sessionId in (select s.id"
                            + " from AccessSession s where s.customerAccountId=:owner"
                            + " and s.kind='customer')")
                    .setParameter("owner", a.id)
                    .executeUpdate();
            em.createQuery("delete from AccessSession s where s.customerAccountId=:owner" + " and s.kind='customer'")
                    .setParameter("owner", a.id)
                    .executeUpdate();
            audit.record(new AuditAction(
                    auth.actor(), "identity.account_deleted", "customer", a.id.toString(), null, null, null));
            em.remove(a);
            em.flush();
            events.publishEvent(new CustomerDataErased(TenantContext.require().id(), erased));
            return null;
        });
    }

    private Optional<UUID> matches(String payload, String numberHash) {
        var node = json.readTree(payload);
        if (!node.isObject() || !node.has("challengeId") || !node.has("encrypted") || node.size() != 2)
            return Optional.empty();
        try {
            CustomerAccess.Envelope envelope = json.readValue(payload, CustomerAccess.Envelope.class);
            CustomerAccess.ClearMessage clear = json.readValue(
                    crypto.decrypt(
                            "customer-code:" + TenantContext.require().id() + ":" + envelope.challengeId(),
                            envelope.encrypted()),
                    CustomerAccess.ClearMessage.class);
            return crypto.equalHash(numberHash, crypto.hash("customer-number", clear.mobile()))
                    ? Optional.of(envelope.challengeId())
                    : Optional.empty();
        } catch (RuntimeException ex) {
            // A recognized owner envelope must never be silently omitted from privacy erasure.
            throw new ApplicationProblemException(500, ErrorCode.INTERNAL_ERROR);
        }
    }

    private static ApplicationProblemException field(String field, String detail) {
        return new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, List.of(new FieldProblem(field, detail)));
    }

    private static Preferences preferences(CustomerAccount a) {
        return new Preferences(a.notifySms, a.notifyWhatsApp, a.notifyEmail);
    }

    private static Profile view(CustomerAccount a) {
        return new Profile(
                a.id,
                a.version,
                a.name,
                a.mobile,
                a.email,
                a.birthday,
                a.preferredLanguage,
                preferences(a),
                a.marketingConsent);
    }
}
