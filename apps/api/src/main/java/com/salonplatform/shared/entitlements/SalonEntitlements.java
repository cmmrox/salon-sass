package com.salonplatform.shared.entitlements;

import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.List;
import java.util.Optional;

/**
 * What a salon's plan allows (PLT-04; docs/architecture/operator-console.md, "Entitlements"), as
 * other modules ask it inside their own tenant transactions. {@code shared} owns the question and
 * {@code platform}, which owns plans and the registry, answers it, so a module that platform already
 * depends on (identity) can enforce a limit without depending on platform. Nothing in a client is the
 * source of an entitlement.
 */
public interface SalonEntitlements {

    /** A plan's limits and features. */
    record Limits(int staffSeats, int branches, int messageAllowance, boolean customDomain, boolean dedicatedApp) {}

    /** The limits named in an {@code entitlement_exceeded} problem. */
    String STAFF_SEATS = "staffSeats";

    /**
     * The limits of the current salon's plan, read in the caller's writable tenant transaction. The
     * salon's registry row stays share-locked until that transaction ends, so a concurrent plan move
     * cannot interleave with an enforcement made under the old limits.
     *
     * @return the plan's limits, or nothing when the salon has no plan (a legacy salon), which limits
     *         nothing
     * @throws IllegalStateException outside a writable transaction with a salon set
     */
    Optional<Limits> forCurrentSalon();

    /** 409 {@code entitlement_exceeded}, naming the limit and what the plan allows. */
    static ApplicationProblemException exceeded(String limit, long allowed) {
        return new ApplicationProblemException(
                409, ErrorCode.ENTITLEMENT_EXCEEDED, List.of(new FieldProblem(limit, "The plan allows " + allowed)));
    }
}
