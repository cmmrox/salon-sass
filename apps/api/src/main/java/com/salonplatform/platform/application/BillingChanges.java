package com.salonplatform.platform.application;

import java.util.ArrayList;
import java.util.List;

/** What one billing pass did to one subscription, for the run-now hook's answer and the log. */
public record BillingChanges(List<String> issued, List<String> reminders, List<String> charges, List<String> states) {

    public static BillingChanges none() {
        return new BillingChanges(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    }

    public boolean any() {
        return !issued.isEmpty() || !reminders.isEmpty() || !charges.isEmpty() || !states.isEmpty();
    }
}
