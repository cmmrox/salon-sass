import type { components } from '@salon/api-contracts/booking';

/** The booking rules as settings staff keep them: the version a save is made from, the rules or null (F04-S04). */
export type BookingRulesSettings = components['schemas']['BookingRulesSettings'];

/** One version's rules, as the API answers them. */
export type BookingRules = components['schemas']['BookingRules'];

/** The save the BFF passes on. */
export type BookingRulesInput = components['schemas']['BookingRulesInput'];

/** A page of the rules' versions, newest first. */
export type BookingRulesHistory = components['schemas']['BookingRulesHistory'];

/** The salon's current rules as customers see them: the Services page says a "Salon rule" deposit from them (F05-S02). */
export type PublicBookingRules = components['schemas']['PublicBookingRules'];

/** A booking or hold as staff see it (F07-S01): reference, state, times in the salon's zone, the customer and source. */
export type Booking = components['schemas']['Booking'];

/** One booking with its history, oldest first, and the actions the caller may take on it now. */
export type BookingDetail = components['schemas']['BookingDetail'];

/** One change in a booking's history. */
export type BookingHistoryEntry = components['schemas']['BookingHistoryEntry'];

/** A page of one day's bookings and holds, in time order. */
export type BookingPage = components['schemas']['BookingPage'];

/** A booking's state, as the API names it. */
export type BookingState = Booking['state'];

/** Where a booking came from, as the API names it. */
export type BookingChannel = Booking['channel'];

/** A change the API may allow on a booking now. */
export type BookingAction = BookingDetail['allowedActions'][number];

/** Why a cancellation was made. */
export type CancelReason = components['schemas']['CancelBooking']['reason'];

/** The free times of a day for a service, each with who would take it, or why there are none (F07-S02). */
export type Availability = components['schemas']['Availability'];

/** The days the salon is open in a period (F07-S02). */
export type OpenDays = components['schemas']['OpenDays'];

/**
 * A service New appointment offers, as the surface hands it in from the catalogue: its price (the lowest and highest
 * when its variants differ) and its variants, each with its own price.
 */
export type BookableService = {
  id: string;
  name: string;
  categoryId: string;
  currency: string;
  priceMinMinor: number;
  priceMaxMinor: number;
  variantSetName: string | null;
  variants: { id: string; name: string; priceMinor: number }[];
};

/** A team member, as the surface hands them in from the team: what they perform, by service and whole category. */
export type BookableMember = { id: string; name: string; serviceIds: string[]; categoryIds: string[] };
