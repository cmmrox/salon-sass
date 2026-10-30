package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.domain.BillingTerms;
import com.salonplatform.shared.web.FieldProblem;
import java.util.List;

/** The terms of a new subscription as an operator enters them, at salon creation or when billing starts. */
final class SubscriptionTerms {

    private SubscriptionTerms() {}

    /** The validated amount and method of a new subscription; field names carry the given prefix. */
    static void validate(String prefix, Long amount, String method, List<FieldProblem> errors) {
        if (!BillingTerms.validAmount(amount)) {
            errors.add(new FieldProblem(
                    prefix + "monthlyAmount",
                    "must be a whole number of minor units from 0 to " + BillingTerms.MAX_AMOUNT));
        }
        if (!BillingTerms.validMethod(method)) {
            errors.add(new FieldProblem(prefix + "paymentMethod", "must be card or bank_transfer"));
        }
    }
}
