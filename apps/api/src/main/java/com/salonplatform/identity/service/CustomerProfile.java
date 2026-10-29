package com.salonplatform.identity.service;

import com.salonplatform.identity.CustomerDataErased;
import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.domain.Identifiers;
import com.salonplatform.identity.entity.CustomerAccount;
import com.salonplatform.identity.entity.CustomerChallenge;
import com.salonplatform.identity.repository.CustomerAccountRepository;
import com.salonplatform.identity.repository.CustomerChallengeRepository;
import com.salonplatform.identity.repository.CustomerDataErasure;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.time.SalonTime;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** Own profile and data rights; all privacy effects share the identity security transaction. */
@Service
@RequiredArgsConstructor
public class CustomerProfile {

    private final IdentityWork work;
    private final SessionAccess sessions;
    private final CustomerAccountRepository customers;
    private final CustomerChallengeRepository challenges;
    private final CustomerDataErasure erasure;
    private final IdentityCrypto crypto;
    private final JsonMapper json;
    private final Clock clock;
    private final AuditTrail audit;
    private final Outbox outbox;
    private final ApplicationEventPublisher events;

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
    public record Export(Instant generatedAt, Profile profile, List<SessionView> devices) {}

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
            CustomerAccount account = auth.customer();
            String name = change.name() == null ? null : change.name().trim();
            if (name == null || name.isBlank() || name.codePointCount(0, name.length()) > 120) {
                throw IdentityProblems.invalidField("name", "must contain 1 to 120 characters");
            }
            String email;
            try {
                email = Identifiers.email(change.email());
            } catch (IllegalArgumentException ex) {
                throw IdentityProblems.invalidField("email", "must be a valid email address");
            }
            if (change.birthday() != null
                    && change.birthday()
                            .isAfter(SalonTime.toLocal(
                                            clock.instant(),
                                            TenantContext.require().timeZone())
                                    .toLocalDate())) {
                throw IdentityProblems.invalidField("birthday", "must not be in the future");
            }
            if (!Set.of("en", "si", "ta").contains(change.preferredLanguage())) {
                throw IdentityProblems.invalidField("preferredLanguage", "must be en, si or ta");
            }
            if (account.getVersion() != change.version()) {
                throw IdentityProblems.conflict();
            }
            List<String> changed = new ArrayList<>();
            if (!Objects.equals(account.getName(), name)) {
                changed.add("name");
            }
            if (!Objects.equals(account.getEmail(), email)) {
                changed.add("email");
            }
            if (!Objects.equals(account.getBirthday(), change.birthday())) {
                changed.add("birthday");
            }
            if (!account.getPreferredLanguage().equals(change.preferredLanguage())) {
                changed.add("preferredLanguage");
            }
            if (!preferences(account).equals(change.preferences())) {
                changed.add("notificationPreferences");
            }
            if (account.isMarketingConsent() != change.marketingConsent()) {
                changed.add("marketingConsent");
            }
            long previous = account.getVersion();
            account.updateProfile(
                    new CustomerAccount.Profile(
                            name,
                            email,
                            change.birthday(),
                            change.preferredLanguage(),
                            change.preferences().sms(),
                            change.preferences().whatsapp(),
                            change.preferences().email(),
                            change.marketingConsent()),
                    clock.instant());
            customers.flush();
            audit.record(new AuditAction(
                    auth.actor(),
                    "identity.profile_updated",
                    "customer",
                    account.getId().toString(),
                    null,
                    Map.of("version", previous),
                    Map.of("version", account.getVersion(), "changedFields", changed)));
            return view(account);
        });
    }

    public Export export() {
        return work.run(() -> {
            SessionAccess.Auth auth = sessions.customerAuth();
            List<SessionView> devices = new ArrayList<>();
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
            if (!confirm) {
                throw new ApplicationProblemException(
                        400, ErrorCode.CONFIRMATION_REQUIRED, List.of(new FieldProblem("confirm", "must be true")));
            }
            CustomerAccount account = auth.customer();
            String numberHash = crypto.hash("customer-number", account.getMobile());
            Set<UUID> erased = new HashSet<>(outbox.eraseMatching(
                    Set.of("sms", "whatsapp"), payload -> matches(payload, numberHash), clock.instant()));
            challenges.findByNumberHash(numberHash).stream()
                    .map(CustomerChallenge::getId)
                    .forEach(erased::add);
            erasure.eraseChallengesOfNumber(numberHash);
            erasure.eraseSessionsOf(account.getId());
            audit.record(new AuditAction(
                    auth.actor(),
                    "identity.account_deleted",
                    "customer",
                    account.getId().toString(),
                    null,
                    null,
                    null));
            customers.delete(account);
            customers.flush();
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

    private static Preferences preferences(CustomerAccount account) {
        return new Preferences(account.isNotifySms(), account.isNotifyWhatsApp(), account.isNotifyEmail());
    }

    private static Profile view(CustomerAccount account) {
        return new Profile(
                account.getId(),
                account.getVersion(),
                account.getName(),
                account.getMobile(),
                account.getEmail(),
                account.getBirthday(),
                account.getPreferredLanguage(),
                preferences(account),
                account.isMarketingConsent());
    }
}
