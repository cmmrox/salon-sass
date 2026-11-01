package com.salonplatform.catalogue.service;

import java.util.List;
import java.util.UUID;

/**
 * The salon's bookable menu, public (CAT-01, CAT-02, F05-S01 AC-11): its categories in its order, each with its
 * services that are bookable online, by name. A category without such services is left out.
 */
public interface MenuService {

    /**
     * One bookable service as customers see it.
     *
     * @param durationMinutes the customer's time: every segment but the buffer
     * @param photoId its photo, null when it has none
     */
    record MenuItem(
            UUID id,
            String name,
            String description,
            long priceMinor,
            String currency,
            int durationMinutes,
            UUID photoId,
            UUID termsVersionId) {}

    record MenuCategory(UUID id, String name, List<MenuItem> services) {}

    /** @param currency the salon's currency */
    record Menu(String currency, List<MenuCategory> categories) {}

    Menu menu();
}
