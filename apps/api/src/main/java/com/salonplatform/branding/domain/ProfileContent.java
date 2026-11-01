package com.salonplatform.branding.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a salon's business profile holds, in a draft or in its published copy: the salon-wide
 * description and links, and each branch's contact details. Every field is optional (null when not
 * set), so a salon can publish a partial profile.
 *
 * @param description the salon's description
 * @param instagram its Instagram link, https
 * @param facebook its Facebook link, https
 * @param website its website, https
 * @param branches each branch's contact details, one per branch, ordered by branch id
 */
public record ProfileContent(
        String description, String instagram, String facebook, String website, List<BranchContact> branches) {

    public ProfileContent {
        branches = branches.stream()
                .sorted(Comparator.comparing(BranchContact::branchId))
                .toList();
    }

    /** Whether nothing at all is set. */
    public boolean isEmpty() {
        return description == null
                && instagram == null
                && facebook == null
                && website == null
                && branches.stream().allMatch(BranchContact::isEmpty);
    }

    /** The same branches with nothing set: a draft with no published copy to go back to. */
    public ProfileContent emptied() {
        return new ProfileContent(
                null,
                null,
                null,
                null,
                branches.stream().map(b -> BranchContact.empty(b.branchId())).toList());
    }

    /**
     * The names of the fields that differ from another profile (never their values), in a stable order:
     * {@code description}, {@code links.instagram}, {@code branches[<id>].phone}. A branch missing from
     * one side counts as one with nothing set.
     */
    public List<String> changedFrom(ProfileContent other) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(description, other.description)) changed.add("description");
        if (!Objects.equals(instagram, other.instagram)) changed.add("links.instagram");
        if (!Objects.equals(facebook, other.facebook)) changed.add("links.facebook");
        if (!Objects.equals(website, other.website)) changed.add("links.website");
        Map<UUID, BranchContact> theirs = other.byBranch();
        Map<UUID, BranchContact> ours = byBranch();
        SortedSet<UUID> ids = new TreeSet<>(ours.keySet());
        ids.addAll(theirs.keySet());
        for (UUID id : ids) {
            BranchContact mine = ours.getOrDefault(id, BranchContact.empty(id));
            changed.addAll(mine.changedFrom(theirs.getOrDefault(id, BranchContact.empty(id))));
        }
        return List.copyOf(changed);
    }

    Map<UUID, BranchContact> byBranch() {
        return branches.stream().collect(Collectors.toMap(BranchContact::branchId, Function.identity()));
    }
}
