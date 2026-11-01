package com.salonplatform.branding.service;

import com.salonplatform.branding.domain.BrandRules;

/**
 * The salon's brand as its settings staff keep it (F04-S02): a draft only they see, and the published brand
 * everything else is served, on F04-S01's pattern. The salon is the request's; the permission is checked
 * here (decision 085). Every change names the draft's version it was made from, and each throws
 * {@link com.salonplatform.shared.web.ApplicationProblemException} 403 {@code permission_denied} without
 * {@code settings.manage} and 409 {@code conflict} when the draft moved past that version.
 */
public interface BrandService {

    /**
     * The salon's brand draft.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 403 {@code permission_denied}
     *     without {@code settings.read}
     */
    BrandDraftView draft();

    /**
     * Saves the brand as the salon's draft.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 {@code bad_request} naming each
     *     field that broke a rule, with nothing saved
     */
    BrandDraftView save(long version, BrandRules.Input brand);

    /** Sets the draft to the default branding: the default colour and no tagline, logo or cover; the name kept. */
    BrandDraftView reset(long version);

    /**
     * Makes the draft the published brand, in one step.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 409 {@code invalid_state} when there
     *     is nothing to publish
     */
    BrandDraftView publish(long version);

    /** Returns the draft to the published brand. The published brand does not change. */
    BrandDraftView discard(long version);
}
