package com.salonplatform.branding.service.impl;

import com.salonplatform.branding.domain.BranchContact;
import com.salonplatform.branding.domain.ProfileContent;
import com.salonplatform.branding.entity.Branch;
import com.salonplatform.branding.entity.BranchContactDraft;
import com.salonplatform.branding.entity.ProfileDraft;
import com.salonplatform.branding.entity.PublishedBranchContact;
import com.salonplatform.branding.entity.PublishedProfile;
import com.salonplatform.branding.repository.BranchContactDraftRepository;
import com.salonplatform.branding.repository.BranchRepository;
import com.salonplatform.branding.repository.PublishedBranchContactRepository;
import com.salonplatform.branding.repository.PublishedProfileRepository;
import com.salonplatform.shared.tenancy.TenantNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reads the current salon's profile rows into their content, inside the caller's tenant transaction: the
 * draft's and the published copy's, each with one entry per branch of the salon. A branch with no row of
 * its own in a copy counts as one with nothing set.
 */
@Component
@RequiredArgsConstructor
class ProfileRecords {

    private final BranchRepository branches;
    private final BranchContactDraftRepository contactDrafts;
    private final PublishedProfileRepository published;
    private final PublishedBranchContactRepository publishedContacts;

    /** The salon's default branch; a salon without one is no salon this module can serve. */
    UUID defaultBranchId() {
        return branches.findDefault().map(Branch::getId).orElseThrow(TenantNotFoundException::new);
    }

    List<UUID> branchIds() {
        return branches.findAllInOrder().stream().map(Branch::getId).toList();
    }

    Map<UUID, BranchContactDraft> draftContacts() {
        return contactDrafts.findAllInOrder().stream()
                .collect(Collectors.toMap(BranchContactDraft::getBranchId, Function.identity()));
    }

    ProfileContent draftContent(ProfileDraft draft, Map<UUID, BranchContactDraft> contacts) {
        return draft.content(branchIds().stream()
                .map(id -> Optional.ofNullable(contacts.get(id))
                        .map(BranchContactDraft::toContact)
                        .orElseGet(() -> BranchContact.empty(id)))
                .toList());
    }

    Optional<PublishedProfile> publishedProfile() {
        return published.findCurrent();
    }

    Map<UUID, PublishedBranchContact> publishedContacts() {
        return publishedContacts.findAllInOrder().stream()
                .collect(Collectors.toMap(PublishedBranchContact::getBranchId, Function.identity()));
    }

    ProfileContent publishedContent(PublishedProfile profile, Map<UUID, PublishedBranchContact> contacts) {
        return profile.content(branchIds().stream()
                .map(id -> Optional.ofNullable(contacts.get(id))
                        .map(PublishedBranchContact::toContact)
                        .orElseGet(() -> BranchContact.empty(id)))
                .toList());
    }

    /** The published content, when the salon has published. */
    Optional<ProfileContent> publishedContent() {
        return publishedProfile().map(profile -> publishedContent(profile, publishedContacts()));
    }
}
