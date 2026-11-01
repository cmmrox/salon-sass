package com.salonplatform.catalogue.repository;

import com.salonplatform.shared.persistence.PlatformDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Catalogue's {@code SECURITY DEFINER} function on the platform connection, called inside the platform transaction
 * that creates a salon: the platform pool and a definer function, named direct-SQL exceptions
 * (docs/engineering/backend.md). The function is given the new salon and sets it for itself.
 */
@Component
@RequiredArgsConstructor
public class CataloguePlatformFunctions {

    private final ObjectProvider<PlatformDatabase> platform;

    /**
     * Gives the salon the default categories on its default branch through
     * {@code catalogue_add_default_categories}, which leaves a salon that already has them unchanged.
     */
    public void addDefaultCategories(UUID salonId, Instant at) {
        platform.getObject()
                .jdbc()
                .sql("SELECT catalogue_add_default_categories(?, ?)::text")
                .params(salonId, Timestamp.from(at))
                .query(String.class)
                .optional();
    }
}
