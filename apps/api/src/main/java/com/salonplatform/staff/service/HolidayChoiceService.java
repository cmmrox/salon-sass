package com.salonplatform.staff.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * A salon's decisions about the operator's published holidays (F04-S03). A published year is "to review"
 * until the salon accepts its choices for it; nothing of it applies before. After acceptance one day's choice
 * changes on its own and applies at once, and a later revision puts back to review only the days it added or
 * changed. The branch is the salon's default branch.
 */
public interface HolidayChoiceService {

    /** A choice as a request sends it, validated here. */
    record ChoiceInput(String date, String choice, String opens, String closes) {}

    /**
     * One published day and the salon's decision.
     *
     * @param choice the salon's latest choice for the day ({@code closed}, {@code open} or {@code special}),
     *     null when it has never made one; when {@code toReview}, it is not applied
     * @param toReview whether no choice applies to the day as it is published now
     * @param past whether the day is before the salon's today
     */
    record HolidayDay(
            LocalDate date,
            String name,
            String kind,
            String choice,
            LocalTime opens,
            LocalTime closes,
            boolean toReview,
            boolean past) {}

    /**
     * A published year and the salon's review of it.
     *
     * @param reviewed whether the salon has accepted its choices and no day still to come is to review
     * @param version the version an acceptance is made from: 0 before the first acceptance
     */
    record HolidayYear(
            int year, int revision, Instant publishedAt, boolean reviewed, long version, List<HolidayDay> days) {}

    /** The published years from the salon's current year on, oldest first. */
    List<HolidayYear> years();

    /**
     * Accepts the salon's choices for the year: one for every day still to come that is to review, and any
     * other day still to come. 400 naming each bad choice; 404 when the year is not published; 409
     * {@code conflict} when the review changed since {@code version}.
     */
    HolidayYear accept(int year, Long version, List<ChoiceInput> choices);

    /**
     * Changes one day's choice after the year's first acceptance. 404 when the year is not published or the
     * date is not one of its days; 409 {@code invalid_state} before acceptance or once the day has passed.
     */
    HolidayYear choose(int year, String date, ChoiceInput choice);
}
