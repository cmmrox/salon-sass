package com.salonplatform.branding.service.impl;

import com.salonplatform.branding.SalonBranch;
import com.salonplatform.branding.SalonBranches;
import com.salonplatform.branding.repository.BranchRepository;
import com.salonplatform.shared.persistence.ReadOnlyWork;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.tenancy.TenantNotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Opens its own read-only tenant transaction, with the salon resolved first, as a provided interface
 * does (docs/engineering/backend.md, "Provided interfaces"). The tenant filter and row-level security
 * leave only the current salon's branches, so another salon's branch is simply not found.
 */
@Service
class SalonBranchesImpl implements SalonBranches {

    private final BranchRepository branches;
    private final ReadOnlyWork reading;

    SalonBranchesImpl(BranchRepository branches, ReadOnlyWork reading) {
        this.branches = branches;
        this.reading = reading;
    }

    @Override
    public SalonBranch defaultBranch() {
        TenantContext.require();
        return reading.read(() -> branches.findDefault()
                .map(branch -> new SalonBranch(branch.getId(), branch.getName(), branch.isDefault()))
                .orElseThrow(TenantNotFoundException::new));
    }

    @Override
    public boolean belongsToCurrentSalon(UUID branchId) {
        TenantContext.require();
        if (branchId == null) {
            return false;
        }
        return Boolean.TRUE.equals(reading.read(() -> branches.existsById(branchId)));
    }
}
