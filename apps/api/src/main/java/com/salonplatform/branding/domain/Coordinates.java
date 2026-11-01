package com.salonplatform.branding.domain;

import java.math.BigDecimal;

/**
 * A map position: latitude and longitude in decimal degrees, each with at most six decimal places
 * (about 0.1 m), the precision they are stored with.
 */
public record Coordinates(BigDecimal latitude, BigDecimal longitude) {

    /** Whether two positions are the same place, whatever trailing zeros either is written with. */
    boolean samePlaceAs(Coordinates other) {
        return other != null && latitude.compareTo(other.latitude) == 0 && longitude.compareTo(other.longitude) == 0;
    }
}
