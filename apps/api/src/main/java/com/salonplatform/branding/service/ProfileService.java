package com.salonplatform.branding.service;

import com.salonplatform.branding.domain.ProfileInput;

/**
 * The salon's business profile as its settings staff keep it (F04-S01): a draft that only they see, and
 * the published copy everything else is served. The salon is the request's; the permission is checked
 * here, not only by the caller (decision 085). Every change names the draft's version it was made from.
 */
public interface ProfileService {

    /**
     * The salon's draft.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 403 {@code permission_denied}
     *     without {@code settings.read}
     */
    ProfileDraftView draft();

    /**
     * Saves the profile as the salon's draft. Branches not named keep their draft details.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 403 {@code permission_denied}
     *     without {@code settings.manage}; 409 {@code conflict} when the draft moved past {@code version};
     *     400 {@code bad_request} naming each field that broke a rule, with nothing saved
     */
    ProfileDraftView save(long version, ProfileInput profile);

    /**
     * Makes the draft the published profile, in one step.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 403 {@code permission_denied}
     *     without {@code settings.manage}; 409 {@code conflict} when the draft moved past {@code version};
     *     409 {@code invalid_state} when there is nothing to publish
     */
    ProfileDraftView publish(long version);

    /**
     * Returns the draft to the published profile, or to empty before the first publication. The
     * published profile does not change.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 403 {@code permission_denied}
     *     without {@code settings.manage}; 409 {@code conflict} when the draft moved past {@code version}
     */
    ProfileDraftView discard(long version);
}
