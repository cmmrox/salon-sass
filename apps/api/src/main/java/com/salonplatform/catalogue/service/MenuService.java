package com.salonplatform.catalogue.service;

import com.salonplatform.catalogue.ServiceDeposit;
import java.util.List;
import java.util.UUID;

/**
 * The salon's bookable menu, public (CAT-01, CAT-02, CAT-03, CAT-06; F05-S01 AC-11, F05-S02 AC-3, AC-4, AC-6): its
 * categories in its order, each with its services that are bookable online, by name, and the Popular list. A
 * category without such services is left out.
 */
public interface MenuService {

    /**
     * One variant of a bookable service, as customers see it.
     *
     * @param durationMinutes the customer's time: every segment but the buffer
     */
    record MenuVariant(UUID id, String name, long priceMinor, int durationMinutes) {}

    /**
     * One bookable service as customers see it.
     *
     * @param priceMinor with variants, the lowest
     * @param durationMinutes the customer's time: every segment but the buffer; with variants, the shortest
     * @param photoId its photo, null when it has none
     * @param variantSetName null without variants
     * @param variants in order; empty without variants
     */
    record MenuItem(
            UUID id,
            String name,
            String description,
            long priceMinor,
            String currency,
            int durationMinutes,
            UUID photoId,
            UUID termsVersionId,
            String variantSetName,
            List<MenuVariant> variants,
            long priceMinMinor,
            long priceMaxMinor,
            int durationMinMinutes,
            int durationMaxMinutes,
            ServiceDeposit deposit) {

        public MenuItem {
            variants = List.copyOf(variants);
        }
    }

    record MenuCategory(UUID id, String name, List<MenuItem> services) {}

    /**
     * @param currency the salon's currency
     * @param popular the Popular services' ids, in order, at most six
     */
    record Menu(String currency, List<MenuCategory> categories, List<UUID> popular) {}

    Menu menu();
}
