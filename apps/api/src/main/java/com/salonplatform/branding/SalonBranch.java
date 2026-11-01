package com.salonplatform.branding;

import java.util.UUID;

/**
 * One of the current salon's branches.
 *
 * @param id the branch's id, which other modules hold as their branch scope
 * @param name its name; the default branch is named after the salon, and no R1 screen shows it
 * @param isDefault whether it is the salon's default branch
 */
public record SalonBranch(UUID id, String name, boolean isDefault) {}
