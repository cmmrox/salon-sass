package com.salonplatform.branding.service;

import com.salonplatform.branding.domain.ProfileContent;
import java.util.UUID;

/**
 * A business profile as a reader is given it: its content and which of its branches is the salon's
 * default.
 *
 * @param content the description, links and each branch's contact details
 * @param defaultBranchId the salon's default branch
 */
public record ProfileView(ProfileContent content, UUID defaultBranchId) {}
