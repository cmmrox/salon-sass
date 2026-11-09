package com.salonplatform.staff.service.impl;

import com.salonplatform.catalogue.CatalogueListing;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import com.salonplatform.staff.domain.PerformedServices;
import com.salonplatform.staff.entity.Member;
import com.salonplatform.staff.service.TeamMemberService.MemberInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A person as a request sends them, checked against F06-S01's AC-1 and AC-3: every broken rule is a field error naming
 * it, and nothing is saved. Text is trimmed, and an empty optional field is none. Whether a linked account may be
 * linked is checked in the transaction, under the salon's identity lock.
 */
final class MemberValidation {

    static final int NAME_MIN = 2;
    static final int NAME_MAX = 40;
    static final int TITLE_MAX = 40;
    static final int HIGHLIGHTS_MAX = 60;
    static final int BIO_MAX = 140;

    /**
     * The checked values.
     *
     * @param signInAccountId the account to link, or null for no sign-in
     */
    record Valid(Member.Profile profile, PerformedServices.Choice choice, UUID signInAccountId) {}

    private MemberValidation() {}

    /**
     * @param shownOnlineWhenAbsent what an absent {@code shownOnline} means, or null when it is required
     */
    static Valid check(MemberInput input, CatalogueListing listing, Boolean shownOnlineWhenAbsent) {
        List<FieldProblem> errors = new ArrayList<>();
        String name = trimmed(input.name());
        int length = name == null ? 0 : length(name);
        if (length < NAME_MIN || length > NAME_MAX) {
            errors.add(new FieldProblem("name", "must be 2 to 40 characters"));
        }
        String title = optional("title", input.title(), TITLE_MAX, errors);
        String highlights = optional("highlights", input.highlights(), HIGHLIGHTS_MAX, errors);
        String bio = optional("bio", input.bio(), BIO_MAX, errors);
        Boolean shownOnline = input.shownOnline() == null ? shownOnlineWhenAbsent : input.shownOnline();
        if (shownOnline == null) {
            errors.add(new FieldProblem("shownOnline", "is required"));
        }
        List<UUID> categoryIds = input.categoryIds() == null ? List.of() : input.categoryIds();
        List<UUID> serviceIds = input.serviceIds() == null ? List.of() : input.serviceIds();
        if (categoryIds.stream().anyMatch(Objects::isNull)
                || serviceIds.stream().anyMatch(Objects::isNull)
                || !PerformedServices.acceptable(listing, categoryIds, serviceIds)) {
            errors.add(new FieldProblem("services", "must be the salon's categories and services, without overlap"));
        }
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, errors);
        }
        return new Valid(
                new Member.Profile(name, title, highlights, bio, shownOnline),
                new PerformedServices.Choice(Set.copyOf(categoryIds), Set.copyOf(serviceIds)),
                input.signInAccountId());
    }

    /** 400 on {@code signInAccountId}: another salon's account, a removed one, or one already linked. */
    static ApplicationProblemException accountNotLinkable() {
        return new ApplicationProblemException(
                400,
                ErrorCode.BAD_REQUEST,
                List.of(new FieldProblem("signInAccountId", "must be an active account not linked to another person")));
    }

    private static String optional(String field, String value, int max, List<FieldProblem> errors) {
        String kept = trimmed(value);
        if (kept == null || kept.isEmpty()) {
            return null;
        }
        if (length(kept) > max) {
            errors.add(new FieldProblem(field, "must be at most " + max + " characters"));
        }
        return kept;
    }

    private static String trimmed(String value) {
        return value == null ? null : value.strip();
    }

    /** Characters as the database counts them, so that an emoji is one. */
    private static int length(String value) {
        return value.codePointCount(0, value.length());
    }
}
