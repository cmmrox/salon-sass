import type { components } from '@salon/api-contracts/booking';

/** The booking rules as settings staff keep them: the version a save is made from, the rules or null (F04-S04). */
export type BookingRulesSettings = components['schemas']['BookingRulesSettings'];

/** One version's rules, as the API answers them. */
export type BookingRules = components['schemas']['BookingRules'];

/** The save the BFF passes on. */
export type BookingRulesInput = components['schemas']['BookingRulesInput'];

/** A page of the rules' versions, newest first. */
export type BookingRulesHistory = components['schemas']['BookingRulesHistory'];
