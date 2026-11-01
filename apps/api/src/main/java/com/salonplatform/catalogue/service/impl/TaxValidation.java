package com.salonplatform.catalogue.service.impl;

import static com.salonplatform.catalogue.domain.CatalogueLimits.SERVICE_CHARGE_MIN;
import static com.salonplatform.catalogue.domain.CatalogueLimits.VAT_REGISTRATION_NUMBER;

import com.salonplatform.catalogue.TaxSettingsVersion.ServiceCharge;
import com.salonplatform.catalogue.TaxSettingsVersion.Vat;
import com.salonplatform.catalogue.config.CatalogueSettings;
import com.salonplatform.catalogue.service.TaxSettingsService.ServiceChargeInput;
import com.salonplatform.catalogue.service.TaxSettingsService.VatInput;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Tax settings as a request sends them, checked against F05-S03 AC-2 and the prototype's {@code setTax}: every broken
 * rule is a field error naming it, and nothing is saved. VAT on needs a registration number of 1 to 20 letters,
 * digits and hyphens; a service charge on needs a whole rate from 1 to the configured maximum. A part that is off
 * takes no number or rate, and a service charge that is off is not on products: the screen keeps what was typed, the
 * request leaves it out. The checked settings carry the configured names and VAT rate, which every version copies.
 */
final class TaxValidation {

    /** The checked settings, as a version holds them. */
    record Valid(Vat vat, ServiceCharge serviceCharge) {}

    private static final Pattern REGISTRATION_NUMBER = Pattern.compile(VAT_REGISTRATION_NUMBER);
    private static final String NOT_ALLOWED = "is not allowed while it is off";

    private final List<FieldProblem> errors = new ArrayList<>();

    private TaxValidation() {}

    static Valid valid(VatInput vat, ServiceChargeInput serviceCharge, CatalogueSettings.Tax configured) {
        return new TaxValidation().check(vat, serviceCharge, configured);
    }

    private Valid check(VatInput vatInput, ServiceChargeInput chargeInput, CatalogueSettings.Tax configured) {
        Vat vat = vat(vatInput, configured.vat());
        ServiceCharge charge = serviceCharge(chargeInput, configured.serviceCharge());
        if (!errors.isEmpty()) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST, List.copyOf(errors));
        }
        return new Valid(vat, charge);
    }

    private Vat vat(VatInput input, CatalogueSettings.Vat configured) {
        if (input == null) {
            errors.add(new FieldProblem("vat", "is required"));
            return null;
        }
        if (input.registered() == null) {
            errors.add(new FieldProblem("vat.registered", "is required"));
            return null;
        }
        String number = input.registrationNumber();
        if (!input.registered()) {
            if (number != null) {
                errors.add(new FieldProblem("vat.registrationNumber", NOT_ALLOWED));
            }
            return new Vat(configured.name(), configured.ratePercent(), false, null);
        }
        if (number == null || !REGISTRATION_NUMBER.matcher(number).matches()) {
            errors.add(new FieldProblem("vat.registrationNumber", "must be 1 to 20 letters, digits and hyphens"));
            return null;
        }
        return new Vat(configured.name(), configured.ratePercent(), true, number);
    }

    private ServiceCharge serviceCharge(ServiceChargeInput input, CatalogueSettings.ServiceCharge configured) {
        if (input == null) {
            errors.add(new FieldProblem("serviceCharge", "is required"));
            return null;
        }
        if (input.on() == null) {
            errors.add(new FieldProblem("serviceCharge.on", "is required"));
            return null;
        }
        if (input.onProducts() == null) {
            errors.add(new FieldProblem("serviceCharge.onProducts", "is required"));
        }
        Integer percent = input.percent();
        if (!input.on()) {
            if (percent != null) {
                errors.add(new FieldProblem("serviceCharge.percent", NOT_ALLOWED));
            }
            if (Boolean.TRUE.equals(input.onProducts())) {
                errors.add(new FieldProblem("serviceCharge.onProducts", NOT_ALLOWED));
            }
            return new ServiceCharge(configured.name(), false, 0, false);
        }
        if (percent == null || percent < SERVICE_CHARGE_MIN || percent > configured.maxPercent()) {
            errors.add(new FieldProblem(
                    "serviceCharge.percent", "must be a whole percentage from 1 to " + configured.maxPercent()));
            return null;
        }
        return new ServiceCharge(configured.name(), true, percent, Boolean.TRUE.equals(input.onProducts()));
    }
}
