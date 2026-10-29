package com.salonplatform.platform.application;

import com.salonplatform.branding.BrandProvisioning;
import com.salonplatform.identity.IdentityProvisioning;
import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.domain.PlainText;
import com.salonplatform.platform.domain.ProvisioningRegions;
import com.salonplatform.platform.domain.Slugs;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.api.ErrorCode;
import com.salonplatform.shared.api.FieldProblem;
import com.salonplatform.shared.api.NotFoundException;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Creating a salon with its first Owner (PLT-01; docs/architecture/operator-console.md). One platform
 * transaction writes the registry row and its provisioning profile, the immutable plan assignment,
 * the first Owner (identity's facade), the minimal published name and neutral colour (branding's
 * facade), the platform audit entry and the idempotency receipt; any failure rolls back all of it, so
 * no request can resolve a partly created salon. No network call happens inside it, and the Owner's
 * password is validated and hashed before it opens. The operator is never signed in as the Owner.
 */
@Service
public class SalonProvisioning {

    static final String ACTION = "salon.create";

    @Schema(requiredProperties = {"id", "key", "name"})
    public record PlanSummary(UUID id, String key, String name) {}

    @Schema(requiredProperties = {"id", "name", "slug", "hostname", "status", "plan", "createdAt"})
    public record SalonSummary(
            UUID id,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            String slug,
            String hostname,

            @Schema(allowableValues = {"active", "suspended", "closed", "deleted"})
            String status,

            @Schema(
                    types = {"object", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            PlanSummary plan,

            Instant createdAt) {}

    @Schema(requiredProperties = {"items", "page", "size", "total"})
    public record SalonPage(List<SalonSummary> items, int page, int size, long total) {}

    @Schema(
            requiredProperties = {
                "id",
                "version",
                "name",
                "slug",
                "hostname",
                "status",
                "country",
                "currency",
                "timeZone",
                "defaultLanguage",
                "contactEmail",
                "contactPhone",
                "address",
                "plan",
                "ownerEmail",
                "createdAt",
                "updatedAt",
                "closedAt",
                "deleteAt",
                "deletedAt"
            })
    public record SalonDetail(
            UUID id,
            long version,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            String slug,
            String hostname,

            @Schema(allowableValues = {"active", "suspended", "closed", "deleted"})
            String status,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String country,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String currency,

            String timeZone,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String defaultLanguage,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String contactEmail,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String contactPhone,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String address,

            @Schema(
                    types = {"object", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            PlanSummary plan,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String ownerEmail,

            Instant createdAt,
            Instant updatedAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "When the salon was closed (F03-S03)")
            Instant closedAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "When a closed salon's data is deleted: 30 days after closure")
            Instant deleteAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "When a closed salon's data was deleted")
            Instant deletedAt) {}

    @Schema(requiredProperties = {"id", "slug", "hostname", "planId", "createdAt"})
    public record SalonCreated(UUID id, String slug, String hostname, UUID planId, Instant createdAt) {}

    public record OwnerInput(String name, String email, String password) {}

    /** The subscription a new salon starts with (F03-S02): its monthly amount and payment method. */
    public record SubscriptionInput(Long monthlyAmount, String paymentMethod) {}

    public record SalonInput(
            String name,
            String slug,
            String country,
            String currency,
            String timeZone,
            String defaultLanguage,
            String contactEmail,
            String contactPhone,
            String address,
            UUID planId,
            OwnerInput owner,
            SubscriptionInput subscription) {}

    /** The result of a creation: whether it was a replay, and the nonsecret result. */
    public record Created(boolean replayed, SalonCreated salon) {}

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final ProvisioningReceipts receipts;
    private final PlatformAudit audit;
    private final IdentityProvisioning identity;
    private final BrandProvisioning branding;
    private final BootstrapFaults faults;
    private final BillingLedger ledger;
    private final Clock clock;
    private final String platformDomain;

    public SalonProvisioning(
            PlatformDatabase database,
            OperatorWork work,
            OperatorAccess access,
            ProvisioningReceipts receipts,
            PlatformAudit audit,
            IdentityProvisioning identity,
            BrandProvisioning branding,
            BootstrapFaults faults,
            BillingLedger ledger,
            Clock clock,
            @Value("${salon.platform-domain}") String platformDomain) {
        this.database = database;
        this.work = work;
        this.access = access;
        this.receipts = receipts;
        this.audit = audit;
        this.identity = identity;
        this.branding = branding;
        this.faults = faults;
        this.ledger = ledger;
        this.clock = clock;
        this.platformDomain = platformDomain.strip().toLowerCase(Locale.ROOT);
    }

    private String hostname(String slug) {
        return slug + "." + platformDomain;
    }

    private static final String SELECT = """
            SELECT t.id, t.version, t.name, t.slug, t.status, t.country, t.currency, t.time_zone, t.default_language,
                   t.contact_email, t.contact_phone, t.address, t.owner_email, t.created_at, t.updated_at,
                   t.closed_at, t.delete_at, t.deleted_at,
                   p.id AS plan_id, p.key AS plan_key, p.name AS plan_name
              FROM tenant t LEFT JOIN plan p ON p.id = t.plan_id
            """;

    private static PlanSummary plan(ResultSet row) throws SQLException {
        UUID id = row.getObject("plan_id", UUID.class);
        return id == null ? null : new PlanSummary(id, row.getString("plan_key"), row.getString("plan_name"));
    }

    private SalonDetail detail(ResultSet row) throws SQLException {
        return new SalonDetail(
                row.getObject("id", UUID.class),
                row.getLong("version"),
                row.getString("name"),
                row.getString("slug"),
                hostname(row.getString("slug")),
                row.getString("status"),
                row.getString("country"),
                row.getString("currency"),
                row.getString("time_zone"),
                row.getString("default_language"),
                row.getString("contact_email"),
                row.getString("contact_phone"),
                row.getString("address"),
                plan(row),
                row.getString("owner_email"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                instant(row, "closed_at"),
                instant(row, "delete_at"),
                instant(row, "deleted_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public SalonPage list(Paging paging) {
        return work.run(() -> {
            access.require(OperatorPermission.SALON_READ);
            String filter = " WHERE (?::text IS NULL OR strpos(lower(coalesce(t.name, '')), lower(?::text)) > 0"
                    + " OR strpos(t.slug, lower(?::text)) > 0)";
            List<SalonSummary> items = database.jdbc()
                    .query(
                            SELECT + filter + " ORDER BY t.created_at DESC, t.id LIMIT ? OFFSET ?",
                            (row, n) -> new SalonSummary(
                                    row.getObject("id", UUID.class),
                                    row.getString("name"),
                                    row.getString("slug"),
                                    hostname(row.getString("slug")),
                                    row.getString("status"),
                                    plan(row),
                                    row.getTimestamp("created_at").toInstant()),
                            paging.q(),
                            paging.q(),
                            paging.q(),
                            paging.size(),
                            paging.offset());
            Long total = database.jdbc()
                    .queryForObject(
                            "SELECT count(*) FROM tenant t" + filter, Long.class, paging.q(), paging.q(), paging.q());
            return new SalonPage(items, paging.page(), paging.size(), total == null ? 0 : total);
        });
    }

    public SalonDetail get(UUID id) {
        return work.run(() -> {
            access.require(OperatorPermission.SALON_READ);
            return database.jdbc().query(SELECT + " WHERE t.id = ?", (row, n) -> detail(row), id).stream()
                    .findFirst()
                    .orElseThrow(NotFoundException::new);
        });
    }

    /**
     * Creates the salon. Authorization comes first (host is checked before this), then the key and the
     * input, then the receipt; a replay with the same request answers the original result.
     */
    public Created create(String idempotencyKey, SalonInput input) {
        access.check(OperatorPermission.SALON_CREATE);
        UUID key = ProvisioningReceipts.key(idempotencyKey);
        List<FieldProblem> errors = new ArrayList<>();
        Map<String, Object> checked = null;
        try {
            checked = validate(input);
        } catch (ApplicationProblemException ex) {
            errors.addAll(ex.errors());
        }
        OwnerInput owner = input.owner();
        IdentityProvisioning.PreparedOwner preparedOwner = null;
        if (owner != null) {
            try {
                preparedOwner = identity.prepareOwner("owner.", owner.name(), owner.email(), owner.password());
            } catch (ApplicationProblemException ex) {
                errors.addAll(ex.errors());
            }
        }
        if (!errors.isEmpty()) {
            errors.sort(java.util.Comparator.comparing(FieldProblem::field));
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        Map<String, Object> profile = checked;
        IdentityProvisioning.PreparedOwner prepared = preparedOwner;
        Map<String, Object> request = new LinkedHashMap<>(profile);
        request.put("owner.name", prepared.name());
        request.put("owner.email", prepared.email());
        request.put("owner.password", owner.password());
        request.put("subscription.monthlyAmount", input.subscription().monthlyAmount());
        request.put("subscription.paymentMethod", input.subscription().paymentMethod());
        String fingerprint = receipts.fingerprint(ACTION, request);
        try {
            return work.run(() -> {
                var operator = access.require(OperatorPermission.SALON_CREATE);
                var earlier = receipts.replay(operator.id(), ACTION, key, fingerprint);
                if (earlier.isPresent()) {
                    return new Created(true, created(earlier.get().result()));
                }
                UUID planId = (UUID) profile.get("planId");
                Integer plans =
                        database.jdbc().queryForObject("SELECT count(*) FROM plan WHERE id = ?", Integer.class, planId);
                if (plans == null || plans == 0) {
                    throw new NotFoundException();
                }
                Instant now = clock.instant();
                UUID id = UUID.randomUUID();
                String slug = (String) profile.get("slug");
                database.jdbc()
                        .update(
                                """
                        INSERT INTO tenant (id, slug, status, time_zone, name, country, currency, default_language,
                            contact_email, contact_phone, address, owner_email, plan_id, created_at, updated_at)
                        VALUES (?, ?, 'active', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                                id,
                                slug,
                                profile.get("timeZone"),
                                profile.get("name"),
                                profile.get("country"),
                                profile.get("currency"),
                                profile.get("defaultLanguage"),
                                profile.get("contactEmail"),
                                profile.get("contactPhone"),
                                profile.get("address"),
                                prepared.email(),
                                planId,
                                Timestamp.from(now),
                                Timestamp.from(now));
                identity.bootstrapOwner(id, prepared, now);
                faults.reached(BootstrapFaults.Step.OWNER);
                branding.bootstrapBrand(id, (String) profile.get("name"), now);
                faults.reached(BootstrapFaults.Step.BRAND);
                ledger.start(
                        ledger.salon(id, false).orElseThrow(),
                        input.subscription().monthlyAmount(),
                        (String) profile.get("currency"),
                        input.subscription().paymentMethod(),
                        now,
                        PlatformAudit.OPERATOR,
                        operator.id().toString());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("slug", slug);
                details.put("planId", planId.toString());
                details.put(
                        "fields",
                        List.of(
                                "name",
                                "country",
                                "currency",
                                "timeZone",
                                "defaultLanguage",
                                "contactEmail",
                                "contactPhone",
                                "address",
                                "owner",
                                "subscription"));
                audit.record(
                        PlatformAudit.OPERATOR,
                        operator.id().toString(),
                        "salon.created",
                        "salon",
                        id.toString(),
                        details,
                        now);
                SalonCreated result = new SalonCreated(id, slug, hostname(slug), planId, now);
                Map<String, Object> stored = new LinkedHashMap<>();
                stored.put("id", id.toString());
                stored.put("slug", slug);
                stored.put("hostname", result.hostname());
                stored.put("planId", planId.toString());
                stored.put("createdAt", now.toString());
                receipts.store(operator.id(), ACTION, key, fingerprint, id, stored, now);
                faults.reached(BootstrapFaults.Step.RECEIPT);
                return new Created(false, result);
            });
        } catch (DuplicateKeyException ex) {
            String cause = String.valueOf(ex.getMostSpecificCause().getMessage());
            if (cause.contains("tenant_slug_key")) {
                throw new ApplicationProblemException(409, ErrorCode.SLUG_TAKEN);
            }
            throw ex;
        }
    }

    private static SalonCreated created(Map<String, Object> result) {
        return new SalonCreated(
                UUID.fromString((String) result.get("id")),
                (String) result.get("slug"),
                (String) result.get("hostname"),
                UUID.fromString((String) result.get("planId")),
                Instant.parse((String) result.get("createdAt")));
    }

    /** The provisioning profile, normalized, in a fixed order; or 400 naming every bad field. */
    Map<String, Object> validate(SalonInput input) {
        List<FieldProblem> errors = new ArrayList<>();
        String name = PlainText.line(input.name(), 120);
        if (name == null) {
            errors.add(new FieldProblem("name", "must be plain text between 1 and 120 characters"));
        }
        String slug = Slugs.normalize(input.slug());
        if (!Slugs.wellFormed(slug)) {
            errors.add(new FieldProblem(
                    "slug",
                    "must be 3 to 30 lowercase letters, digits and hyphens, starting and "
                            + "ending with a letter or digit"));
        } else if (Slugs.RESERVED.contains(slug)) {
            errors.add(new FieldProblem("slug", "is reserved for the platform"));
        }
        String country =
                input.country() == null ? null : input.country().strip().toUpperCase(Locale.ROOT);
        if (country == null || !ProvisioningRegions.COUNTRIES.contains(country)) {
            errors.add(new FieldProblem("country", "must be a supported ISO 3166-1 two-letter country code"));
        }
        String currency =
                input.currency() == null ? null : input.currency().strip().toUpperCase(Locale.ROOT);
        if (currency == null || !ProvisioningRegions.CURRENCIES.contains(currency)) {
            errors.add(new FieldProblem("currency", "must be a supported ISO 4217 three-letter currency code"));
        }
        String timeZone = input.timeZone() == null ? null : input.timeZone().strip();
        if (!ProvisioningRegions.timeZone(timeZone)) {
            errors.add(new FieldProblem("timeZone", "must be a supported IANA time zone"));
        }
        String language =
                input.defaultLanguage() == null ? null : input.defaultLanguage().strip();
        if (language == null || !ProvisioningRegions.LANGUAGES.contains(language)) {
            errors.add(new FieldProblem("defaultLanguage", "must be en, si or ta"));
        }
        String email = optional(
                input.contactEmail(),
                254,
                "contactEmail",
                "must be a valid email address",
                errors,
                value -> identity.normalizedEmail(value));
        String phone = optional(
                input.contactPhone(),
                32,
                "contactPhone",
                "must be a Sri Lankan mobile number",
                errors,
                value -> identity.normalizedMobile(value));
        String address = null;
        if (input.address() != null && !input.address().isBlank()) {
            address = input.address().length() > 500 ? null : PlainText.block(input.address(), 500);
            if (address == null) {
                errors.add(new FieldProblem("address", "must be plain text of at most 500 characters"));
            }
        }
        if (input.planId() == null) {
            errors.add(new FieldProblem("planId", "is required"));
        }
        if (input.owner() == null) {
            errors.add(new FieldProblem("owner", "is required"));
        }
        if (input.subscription() == null) {
            errors.add(new FieldProblem("subscription", "is required"));
        } else {
            SubscriptionBilling.validateTerms(
                    "subscription.",
                    input.subscription().monthlyAmount(),
                    input.subscription().paymentMethod(),
                    errors);
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", name);
        profile.put("slug", slug);
        profile.put("country", country);
        profile.put("currency", currency);
        profile.put("timeZone", timeZone);
        profile.put("defaultLanguage", language);
        profile.put("contactEmail", email);
        profile.put("contactPhone", phone);
        profile.put("address", address);
        profile.put("planId", input.planId());
        return profile;
    }

    private static String optional(
            String value,
            int max,
            String field,
            String detail,
            List<FieldProblem> errors,
            java.util.function.UnaryOperator<String> normalize) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > max) {
            errors.add(new FieldProblem(field, detail));
            return null;
        }
        try {
            return normalize.apply(value);
        } catch (IllegalArgumentException ex) {
            errors.add(new FieldProblem(field, detail));
            return null;
        }
    }
}
