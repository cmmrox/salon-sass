package com.salonplatform.branding.service;

import java.time.Instant;
import java.util.UUID;

/**
 * The salon's profile draft, as its settings staff see it.
 *
 * @param version the draft's version, which the next save, publication or discard must name
 * @param profile the draft's content
 * @param hasUnpublishedChanges whether the draft differs from the published profile; before the first
 *     publication, whether anything at all is set
 * @param publishedAt when the profile was last published, or null before the first publication
 * @param publishedBy the staff account that last published it, or null before the first publication
 */
public record ProfileDraftView(
        long version, ProfileView profile, boolean hasUnpublishedChanges, Instant publishedAt, UUID publishedBy) {}
