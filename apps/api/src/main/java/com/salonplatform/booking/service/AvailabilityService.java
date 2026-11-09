package com.salonplatform.booking.service;

import com.salonplatform.booking.Availability.Answer;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Free times and open days for staff, with {@code booking.manage} (F07-S02 AC-1 to AC-4, AC-6, AC-9): the calculation of
 * {@link com.salonplatform.booking.Availability} for {@link com.salonplatform.booking.Availability.Audience#STAFF}, on a
 * date from today to one year ahead (decision 108(c)).
 */
public interface AvailabilityService {

    /** The answer, with the salon's zone its instants are shown in. */
    record Times(Answer answer, ZoneId zone) {}

    /**
     * The free times of a service on a date, for one professional or, with no {@code memberId}, "No preference".
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming each wrong field ({@code serviceId},
     *     {@code variantId}, {@code date} before today or more than a year ahead, {@code memberId}); 403; 404; 409
     *     {@code slot_unavailable} or {@code rules_not_set}
     */
    Times freeTimes(String serviceId, String variantId, String date, String memberId);

    /**
     * The dates from {@code from} to {@code to}, both included, that the salon is open: at most 400 days, from today on.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 400 naming {@code from} or {@code to}; 403
     */
    List<LocalDate> openDays(String from, String to);
}
