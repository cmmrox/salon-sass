-- F07-S02, owned by booking: how long a hold lasts and how a "No preference" booking is assigned become booking
-- rules (BKG-03, BKG-04), and a booking records whether the server assigned its professional (for F13's reports).
--
-- Each new column has a default, so every existing version reads 10 minutes and least_booked, which is what a salon
-- that never chose has, and a booking placed before this stage reads as chosen by the desk. Adding a column with a
-- default writes no row, so booking_rules_version's refusal of updates (V23) is untouched and every version keeps the
-- values it was saved with (decision 089). An application still running the previous version keeps inserting without
-- these columns and gets the defaults.

ALTER TABLE booking_rules_version
    ADD COLUMN hold_minutes  integer NOT NULL DEFAULT 10,
    ADD COLUMN no_preference text    NOT NULL DEFAULT 'least_booked',
    ADD CONSTRAINT booking_rules_version_hold_minutes CHECK (hold_minutes IN (5, 10, 15, 20, 30)),
    ADD CONSTRAINT booking_rules_version_no_preference CHECK (no_preference IN ('least_booked', 'most_booked'));

ALTER TABLE booking_appointment
    ADD COLUMN assigned boolean NOT NULL DEFAULT false;
