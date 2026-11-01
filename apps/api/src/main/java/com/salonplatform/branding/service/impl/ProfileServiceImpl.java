package com.salonplatform.branding.service.impl;

import com.salonplatform.branding.config.BrandingSettings;
import com.salonplatform.branding.domain.BranchContact;
import com.salonplatform.branding.domain.ProfileContent;
import com.salonplatform.branding.domain.ProfileInput;
import com.salonplatform.branding.domain.ProfileRules;
import com.salonplatform.branding.entity.BranchContactDraft;
import com.salonplatform.branding.entity.ProfileDraft;
import com.salonplatform.branding.entity.PublishedBranchContact;
import com.salonplatform.branding.entity.PublishedProfile;
import com.salonplatform.branding.repository.ProfileDraftRepository;
import com.salonplatform.branding.repository.PublishedBranchContactRepository;
import com.salonplatform.branding.repository.PublishedProfileRepository;
import com.salonplatform.branding.service.ProfileDraftView;
import com.salonplatform.branding.service.ProfileService;
import com.salonplatform.branding.service.ProfileView;
import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.shared.audit.Actor;
import com.salonplatform.shared.audit.ActorKind;
import com.salonplatform.shared.audit.AuditAction;
import com.salonplatform.shared.audit.AuditTrail;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantNotFoundException;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The salon is resolved and the permission checked before the transaction starts, so that it starts
 * with the salon set (docs/architecture/multi-tenancy.md). Every change takes the draft's row lock first
 * ({@link ProfileDraftRepository#lockCurrent}), checks the version it was sent, and records itself in the
 * audit trail with the names of the fields it changed, never their values.
 */
@Service
class ProfileServiceImpl implements ProfileService {

    static final String READ = "settings.read";
    static final String MANAGE = "settings.manage";
    private static final String SUBJECT = "salon_profile";

    private final Principals principals;
    private final ProfileDraftRepository drafts;
    private final PublishedProfileRepository publishedProfiles;
    private final PublishedBranchContactRepository publishedContacts;
    private final ProfileRecords records;
    private final AuditTrail audit;
    private final Clock clock;
    private final String phoneRegion;
    private final TransactionTemplate writing;
    private final TransactionTemplate reading;

    ProfileServiceImpl(
            Principals principals,
            ProfileDraftRepository drafts,
            PublishedProfileRepository publishedProfiles,
            PublishedBranchContactRepository publishedContacts,
            ProfileRecords records,
            AuditTrail audit,
            Clock clock,
            BrandingSettings settings,
            PlatformTransactionManager transactions) {
        this.principals = principals;
        this.drafts = drafts;
        this.publishedProfiles = publishedProfiles;
        this.publishedContacts = publishedContacts;
        this.records = records;
        this.audit = audit;
        this.clock = clock;
        this.phoneRegion = settings.phoneRegion();
        this.writing = new TransactionTemplate(transactions);
        this.reading = new TransactionTemplate(transactions);
        this.reading.setReadOnly(true);
    }

    @Override
    public ProfileDraftView draft() {
        TenantContext.require();
        principals.require(READ);
        return reading.execute(status -> {
            ProfileDraft draft = drafts.findCurrent().orElseThrow(TenantNotFoundException::new);
            return view(draft, records.draftContacts());
        });
    }

    @Override
    public ProfileDraftView save(long version, ProfileInput input) {
        CurrentTenant salon = TenantContext.require();
        Principal actor = principals.require(MANAGE);
        return writing.execute(status -> {
            ProfileDraft draft = lockAt(version);
            Map<UUID, BranchContactDraft> contacts = records.draftContacts();
            ProfileContent before = records.draftContent(draft, contacts);
            ProfileContent after = switch (ProfileRules.check(input, before, phoneRegion)) {
                case ProfileRules.Accepted accepted -> accepted.content();
                case ProfileRules.Refused refused ->
                    throw new ApplicationProblemException(
                            400,
                            ErrorCode.BAD_REQUEST,
                            refused.violations().stream()
                                    .map(v -> new FieldProblem(v.field(), v.detail()))
                                    .toList());
            };
            Instant now = clock.instant();
            writeDraft(draft, contacts, after, now);
            record(salon, actor, "branding.profile_draft_saved", after.changedFrom(before), draft.getVersion(), now);
            return view(draft, contacts);
        });
    }

    @Override
    public ProfileDraftView publish(long version) {
        CurrentTenant salon = TenantContext.require();
        Principal actor = principals.require(MANAGE);
        return writing.execute(status -> {
            ProfileDraft draft = lockAt(version);
            Map<UUID, BranchContactDraft> contacts = records.draftContacts();
            ProfileContent content = records.draftContent(draft, contacts);
            Optional<PublishedProfile> published = records.publishedProfile();
            Map<UUID, PublishedBranchContact> publishedBranches = records.publishedContacts();
            ProfileContent before = published
                    .map(p -> records.publishedContent(p, publishedBranches))
                    .orElseGet(content::emptied);
            if (!unpublished(content, published.isPresent() ? Optional.of(before) : Optional.empty())) {
                throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
            }
            Instant now = clock.instant();
            UUID by = actor.staffId();
            if (published.isPresent()) {
                published.get().republish(content, by, now);
            } else {
                publishedProfiles.persist(PublishedProfile.first(content, by, now));
            }
            for (BranchContact branch : content.branches()) {
                PublishedBranchContact copy = publishedBranches.get(branch.branchId());
                if (copy == null) {
                    publishedContacts.persist(PublishedBranchContact.first(branch, now));
                } else {
                    copy.republish(branch, now);
                }
            }
            draft.changed(content, now);
            record(salon, actor, "branding.profile_published", content.changedFrom(before), draft.getVersion(), now);
            return new ProfileDraftView(
                    draft.getVersion(), new ProfileView(content, records.defaultBranchId()), false, now, by);
        });
    }

    @Override
    public ProfileDraftView discard(long version) {
        CurrentTenant salon = TenantContext.require();
        Principal actor = principals.require(MANAGE);
        return writing.execute(status -> {
            ProfileDraft draft = lockAt(version);
            Map<UUID, BranchContactDraft> contacts = records.draftContacts();
            ProfileContent before = records.draftContent(draft, contacts);
            ProfileContent after = records.publishedContent().orElseGet(before::emptied);
            Instant now = clock.instant();
            writeDraft(draft, contacts, after, now);
            record(
                    salon,
                    actor,
                    "branding.profile_draft_discarded",
                    after.changedFrom(before),
                    draft.getVersion(),
                    now);
            return view(draft, contacts);
        });
    }

    /** The salon's draft under its row lock, when it is still at the version the change was made from. */
    private ProfileDraft lockAt(long version) {
        ProfileDraft draft = drafts.lockCurrent().orElseThrow(TenantNotFoundException::new);
        if (draft.getVersion() != version) {
            throw new ApplicationProblemException(409, ErrorCode.CONFLICT);
        }
        return draft;
    }

    private static void writeDraft(
            ProfileDraft draft, Map<UUID, BranchContactDraft> contacts, ProfileContent content, Instant now) {
        for (BranchContact branch : content.branches()) {
            BranchContactDraft row = contacts.get(branch.branchId());
            if (row == null) {
                // Every branch has its draft row from its creation (V18, the bootstrap); one without is a
                // salon whose data this module cannot trust.
                throw new IllegalStateException("a branch has no contact draft");
            }
            row.replace(branch, now);
        }
        draft.changed(content, now);
    }

    private ProfileDraftView view(ProfileDraft draft, Map<UUID, BranchContactDraft> contacts) {
        ProfileContent content = records.draftContent(draft, contacts);
        Optional<PublishedProfile> published = records.publishedProfile();
        Optional<ProfileContent> publishedContent =
                published.map(p -> records.publishedContent(p, records.publishedContacts()));
        return new ProfileDraftView(
                draft.getVersion(),
                new ProfileView(content, records.defaultBranchId()),
                unpublished(content, publishedContent),
                published.map(PublishedProfile::getPublishedAt).orElse(null),
                published.map(PublishedProfile::getPublishedBy).orElse(null));
    }

    /**
     * Whether the draft holds something customers do not see yet: a difference from the published copy,
     * or, before the first publication, anything set at all.
     */
    private static boolean unpublished(ProfileContent draft, Optional<ProfileContent> published) {
        return published.map(p -> !draft.changedFrom(p).isEmpty()).orElseGet(() -> !draft.isEmpty());
    }

    private void record(
            CurrentTenant salon, Principal actor, String action, List<String> fields, long version, Instant at) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("changedFields", fields);
        after.put("version", version);
        audit.record(
                new AuditAction(
                        new Actor(ActorKind.STAFF, actor.staffId().toString()),
                        action,
                        SUBJECT,
                        salon.id().toString(),
                        null,
                        null,
                        after),
                at);
    }
}
