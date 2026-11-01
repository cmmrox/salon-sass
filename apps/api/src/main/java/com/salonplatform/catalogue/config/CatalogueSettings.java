package com.salonplatform.catalogue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The catalogue's settings ({@code salon.catalogue}).
 *
 * @param images the limits on an uploaded service photo (F05-S01, decision 093)
 * @param tax the tax names and rates for the salon's country (F05-S03, decision 095)
 */
@ConfigurationProperties("salon.catalogue")
public record CatalogueSettings(Images images, Tax tax) {

    public CatalogueSettings {
        if (images == null) {
            throw new IllegalArgumentException("salon.catalogue.images must be configured");
        }
        if (tax == null) {
            throw new IllegalArgumentException("salon.catalogue.tax must be configured");
        }
    }

    /**
     * @param maxBytes the largest file accepted (5 MB)
     * @param maxPixels the most pixels an image may declare in its header; a larger one is refused before it is
     *     decoded, so a decompression bomb cannot exhaust memory
     * @param concurrentDecodes how many photos an instance decodes at once; one more waits briefly and is then
     *     refused 503
     */
    public record Images(DataSize maxBytes, long maxPixels, int concurrentDecodes) {

        public Images {
            if (maxBytes == null || maxBytes.toBytes() < 1 || maxPixels < 1 || concurrentDecodes < 1) {
                throw new IllegalArgumentException("salon.catalogue.images limits must be positive");
            }
        }
    }

    /**
     * The tax a salon of this country can turn on (CAT-05: "labels from configuration"). A save of a salon's tax
     * settings copies the VAT name and rate and the service charge's name into its version.
     */
    public record Tax(Vat vat, ServiceCharge serviceCharge) {

        public Tax {
            if (vat == null || serviceCharge == null) {
                throw new IllegalArgumentException("salon.catalogue.tax.vat and .service-charge must be configured");
            }
        }
    }

    /**
     * @param name what the tax is called, such as "VAT"
     * @param ratePercent its rate, a whole percentage from 0 to 100 (18 for Sri Lanka)
     * @param registrationLabel what its registration number is called, such as "VAT registration number"
     */
    public record Vat(String name, int ratePercent, String registrationLabel) {

        public Vat {
            named("salon.catalogue.tax.vat.name", name);
            named("salon.catalogue.tax.vat.registration-label", registrationLabel);
            if (ratePercent < 0 || ratePercent > 100) {
                throw new IllegalArgumentException("salon.catalogue.tax.vat.rate-percent must be 0 to 100");
            }
        }
    }

    /**
     * @param name what the service charge is called, such as "Service charge"
     * @param maxPercent the highest rate a salon may charge, 1 to 100 (20 for Sri Lanka); the lowest is 1
     */
    public record ServiceCharge(String name, int maxPercent) {

        public ServiceCharge {
            named("salon.catalogue.tax.service-charge.name", name);
            if (maxPercent < 1 || maxPercent > 100) {
                throw new IllegalArgumentException("salon.catalogue.tax.service-charge.max-percent must be 1 to 100");
            }
        }
    }

    private static void named(String setting, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(setting + " must not be empty");
        }
    }
}
