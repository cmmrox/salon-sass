package com.salonplatform.branding.service.impl;

import com.salonplatform.branding.domain.BrandContent;
import com.salonplatform.branding.domain.BrandRules;
import com.salonplatform.branding.entity.BrandDraft;
import com.salonplatform.branding.entity.SalonBrand;
import com.salonplatform.branding.repository.BrandDraftRepository;
import com.salonplatform.branding.repository.SalonBrandRepository;
import com.salonplatform.branding.service.BrandDraftView;
import com.salonplatform.branding.service.BrandService;
import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantNotFoundException;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * F04-S01's pattern for the brand: the salon resolved and the permission checked before the transaction,
 * every change under the draft's row lock at the version it was sent, and each recorded in the audit trail
 * with the names of the fields it changed. After every change the images' use is brought up to date, for
 * the cleanup job.
 */
@Service
class BrandServiceImpl implements BrandService {

    private static final String SUBJECT = "salon_brand";

    private final Principals principals;
    private final BrandDraftRepository drafts;
    private final SalonBrandRepository published;
    private final BrandRecords records;
    private final SettingsAudit audit;
    private final Clock clock;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    BrandServiceImpl(
            Principals principals,
            BrandDraftRepository drafts,
            SalonBrandRepository published,
            BrandRecords records,
            SettingsAudit audit,
            Clock clock,
            PlatformTransactionManager transactions) {
        this.principals = principals;
        this.drafts = drafts;
        this.published = published;
        this.records = records;
        this.audit = audit;
        this.clock = clock;
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public BrandDraftView draft() {
        TenantContext.require();
        principals.require(ProfileServiceImpl.READ);
        return reading.execute(status -> view(
                drafts.findCurrent().orElseThrow(TenantNotFoundException::new),
                published.findCurrent().orElseThrow(TenantNotFoundException::new)));
    }

    @Override
    public BrandDraftView save(long version, BrandRules.Input input) {
        return change(version, "branding.brand_draft_saved", current -> {
            Set<UUID> named = new HashSet<>();
            for (String id : new String[] {input.logo(), input.cover()}) {
                try {
                    if (id != null) named.add(UUID.fromString(id));
                } catch (IllegalArgumentException ex) {
                    // Refused by the rules below, as an id that names no image.
                }
            }
            return switch (BrandRules.check(input, records.kinds(named))) {
                case BrandRules.Accepted accepted -> accepted.content();
                case BrandRules.Refused refused ->
                    throw new ApplicationProblemException(
                            400,
                            ErrorCode.BAD_REQUEST,
                            refused.violations().stream()
                                    .map(v -> new FieldProblem(v.field(), v.detail()))
                                    .toList());
            };
        });
    }

    @Override
    public BrandDraftView reset(long version) {
        return change(version, "branding.brand_draft_reset", BrandContent::reset);
    }

    @Override
    public BrandDraftView discard(long version) {
        return change(version, "branding.brand_draft_discarded", current -> null);
    }

    @Override
    public BrandDraftView publish(long version) {
        CurrentTenant salon = TenantContext.require();
        Principal actor = principals.require(ProfileServiceImpl.MANAGE);
        return writing.execute(status -> {
            BrandDraft draft = lockAt(version);
            SalonBrand brand = published.findCurrent().orElseThrow(TenantNotFoundException::new);
            BrandContent content = draft.content();
            List<String> changed = content.changedFrom(brand.content());
            if (changed.isEmpty()) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            brand.republish(content, actor.staffId(), now);
            draft.changed(content, now);
            published.flush();
            records.markUse(content, content, now);
            audit.record(
                    salon,
                    actor,
                    "branding.brand_published",
                    SUBJECT,
                    SettingsAudit.changed(changed, draft.getVersion()),
                    now);
            return view(draft, brand);
        });
    }

    /**
     * A change of the draft: the new content from the current one (null: back to the published brand),
     * under the row lock at the version sent, then the images' use and the audit entry.
     */
    private BrandDraftView change(long version, String action, UnaryOperator<BrandContent> next) {
        CurrentTenant salon = TenantContext.require();
        Principal actor = principals.require(ProfileServiceImpl.MANAGE);
        return writing.execute(status -> {
            BrandDraft draft = lockAt(version);
            SalonBrand brand = published.findCurrent().orElseThrow(TenantNotFoundException::new);
            BrandContent before = draft.content();
            BrandContent proposed = next.apply(before);
            BrandContent after = proposed == null ? brand.content() : proposed;
            Instant now = clock.instant();
            draft.changed(after, now);
            drafts.flush();
            records.markUse(after, brand.content(), now);
            audit.record(
                    salon,
                    actor,
                    action,
                    SUBJECT,
                    SettingsAudit.changed(after.changedFrom(before), draft.getVersion()),
                    now);
            return view(draft, brand);
        });
    }

    private BrandDraft lockAt(long version) {
        BrandDraft draft = drafts.lockCurrent().orElseThrow(TenantNotFoundException::new);
        if (draft.getVersion() != version) {
            throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
        }
        return draft;
    }

    private BrandDraftView view(BrandDraft draft, SalonBrand brand) {
        return new BrandDraftView(
                draft.getVersion(),
                records.view(draft.content()),
                !draft.content().changedFrom(brand.content()).isEmpty(),
                brand.getPublishedAt(),
                brand.getPublishedBy());
    }
}
