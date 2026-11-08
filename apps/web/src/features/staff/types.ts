import type { components } from '@salon/api-contracts/staff';

/** The weekly hours as settings staff keep them, with the bookings they leave outside (F04-S03). */
export type HoursSettings = components['schemas']['OpeningHoursSettings'];

/** One weekday of the weekly hours. */
export type Weekday = components['schemas']['OpeningWeekday'];

/** A booking a closure or an hours change affects; it is kept. */
export type AffectedBooking = components['schemas']['AffectedBooking'];

/** The published holiday years and the salon's choices. */
export type SalonHolidays = components['schemas']['SalonHolidays'];

/** One published year and the salon's review of it. */
export type HolidayYear = components['schemas']['SalonHolidayYear'];

/** One published holiday and the salon's decision about it. */
export type HolidayDay = components['schemas']['SalonHolidayDay'];

/** A special closure, as the team sees it. */
export type Closure = components['schemas']['SalonClosure'];

/** The bookings on a date a closure would affect. */
export type ClosureImpact = components['schemas']['ClosureImpact'];

/** What a salon does on a holiday. */
export type HolidayChoiceKind = 'closed' | 'open' | 'special';

/** A person on the team (F06-S01); the two sign-in fields are present only for a caller with `staff.list`. */
export type TeamMember = components['schemas']['TeamMember'];

/** The team, whole. */
export type TeamMemberList = components['schemas']['TeamMemberList'];

/** An active sign-in account, and who it is linked to. */
export type TeamSignInAccount = components['schemas']['TeamSignInAccount'];

/** The salon's active sign-in accounts. */
export type TeamSignInAccountList = components['schemas']['TeamSignInAccountList'];

/** One weekday of a person's week (F06-S02). */
export type TeamWorkday = components['schemas']['TeamWorkday'];

/** A person's week and their date changes. */
export type MemberSchedule = components['schemas']['TeamMemberSchedule'];

/** A change to a person's hours for one date; `note` is present only for a caller with `team.manage`. */
export type MemberDateChange = components['schemas']['TeamMemberDateChange'];

/** A booking a change leaves outside the person's working time; it is kept. */
export type TeamAffectedBooking = components['schemas']['TeamAffectedBooking'];

/** The answer to saving a week. */
export type SavedMemberWeek = components['schemas']['SavedTeamMemberWeek'];

/** The answer to adding a date change. */
export type AddedDateChange = components['schemas']['AddedTeamMemberDateChange'];

/** A person's leave (F06-S03); `type` and `note` are present only for a caller with `team.manage`. */
export type MemberLeave = components['schemas']['TeamMemberLeave'];

/** Part of one date when a person cannot be booked. */
export type MemberBlock = components['schemas']['TeamMemberBlockedTime'];

/** A person's current or next leave, as the card shows it; `type` only with `team.manage`. */
export type NextLeave = components['schemas']['TeamNextLeave'];

/** The answer to adding leave. */
export type AddedLeave = components['schemas']['AddedTeamMemberLeave'];

/** The answer to blocking time. */
export type AddedBlock = components['schemas']['AddedTeamMemberBlockedTime'];

/** The bookings a period of leave or a block would affect, before it is added. */
export type PeriodImpact = components['schemas']['TeamPeriodImpact'];

/** The salon's public opening information, of which the sheet reads the week. */
export type SalonOpeningWeek = components['schemas']['SalonOpeningHours'];

/** A category as the service picker lists it; the workspace surface reads it from the catalogue. */
export type TeamCategory = { id: string; name: string };

/** A service as the service picker lists it. */
export type TeamService = { id: string; name: string; categoryId: string };
