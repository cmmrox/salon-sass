// The staff module's client-safe entry: the Hours & closures tab's parts (F04-S03).
export type { Closure, ClosureImpact, HolidayYear, HoursSettings, SalonHolidays } from './types';
export { AddClosureSheet, type ImpactState } from './components/AddClosureSheet';
export { ClosuresCard } from './components/ClosuresCard';
export { HolidaysCard } from './components/HolidaysCard';
export { OpeningHoursCard } from './components/OpeningHoursCard';
export {
  badDays,
  choiceFormOf,
  choiceSaveOf,
  comingDays,
  dayOfField,
  sameWeek,
  shownYears,
  weekFormOf,
  weekSaveOf,
  type ChoiceForm,
  type DayForm,
  type WeekdayName,
} from './client/hours-form';
