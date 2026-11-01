package com.salonplatform.booking.service.impl;

import com.salonplatform.branding.SalonBranches;
import com.salonplatform.identity.Principal;
import com.salonplatform.identity.Principals;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.TenantContext;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What every booking-rules use case settles before it opens its transaction (docs/architecture/multi-tenancy.md):
 * the salon, who acts and with which permission (decision 085), and the salon's default branch, checked through
 * branding's {@link SalonBranches}.
 */
@Component
class BookingScope {

    static final String READ = "settings.read";
    static final String MANAGE = "settings.manage";

    /** The salon a use case acts for, its default branch, and who acts (null when public). */
    record Scope(CurrentTenant salon, Principal actor, UUID branch) {}

    private final Principals principals;
    private final SalonBranches branches;

    BookingScope(Principals principals, SalonBranches branches) {
        this.principals = principals;
        this.branches = branches;
    }

    /** The scope for a caller who holds the permission. */
    Scope of(String permission) {
        CurrentTenant salon = TenantContext.require();
        Principal actor = principals.require(permission);
        return new Scope(salon, actor, branches.defaultBranch().id());
    }

    /** The scope of a public request, or of another module's call: the request's salon, no one acting. */
    Scope publicly() {
        CurrentTenant salon = TenantContext.require();
        return new Scope(salon, null, branches.defaultBranch().id());
    }
}
