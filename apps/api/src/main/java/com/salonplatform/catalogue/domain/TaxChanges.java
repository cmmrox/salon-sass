package com.salonplatform.catalogue.domain;

import com.salonplatform.catalogue.TaxSettingsVersion.ServiceCharge;
import com.salonplatform.catalogue.TaxSettingsVersion.Vat;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Which tax settings a save changes, named as the API names them, in the tab's order (F05-S03 AC-3). A branch's first
 * version changes every one. The names and the VAT rate copied from configuration count too: a save that changes
 * only them applies a new rate to new bookings and sales, and is not the same as the version before it.
 */
public final class TaxChanges {

    /** Every setting, in the tab's order. */
    public static final List<String> FIELDS = List.of(
            "vat.registered",
            "vat.registrationNumber",
            "vat.ratePercent",
            "vat.name",
            "serviceCharge.on",
            "serviceCharge.percent",
            "serviceCharge.onProducts",
            "serviceCharge.name");

    private TaxChanges() {}

    /**
     * The settings {@code after} changes from {@code before}; every setting when there is no {@code before}, none
     * when the two are the same.
     */
    public static List<String> between(Vat beforeVat, ServiceCharge beforeCharge, Vat vat, ServiceCharge charge) {
        if (beforeVat == null || beforeCharge == null) {
            return FIELDS;
        }
        List<String> changed = new ArrayList<>();
        add(changed, "vat.registered", beforeVat.registered(), vat.registered());
        add(changed, "vat.registrationNumber", beforeVat.registrationNumber(), vat.registrationNumber());
        add(changed, "vat.ratePercent", beforeVat.ratePercent(), vat.ratePercent());
        add(changed, "vat.name", beforeVat.name(), vat.name());
        add(changed, "serviceCharge.on", beforeCharge.on(), charge.on());
        add(changed, "serviceCharge.percent", beforeCharge.percent(), charge.percent());
        add(changed, "serviceCharge.onProducts", beforeCharge.onProducts(), charge.onProducts());
        add(changed, "serviceCharge.name", beforeCharge.name(), charge.name());
        return List.copyOf(changed);
    }

    private static void add(List<String> changed, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(field);
        }
    }
}
