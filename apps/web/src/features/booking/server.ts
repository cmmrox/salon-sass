// The booking module's server entry: the booking-rules calls the workspace BFF makes (F04-S04) and the Bookings
// page's (F07-S03).
import 'server-only';

export { salonBookingsFor } from './server/salon-bookings';
export { salonBookingRulesFor } from './server/salon-booking-rules';
