// The staff module's client-safe entry: the Hours & closures tab's parts (F04-S03) and the Team page's (F06-S01).
export type {
  Closure,
  ClosureImpact,
  HolidayYear,
  HoursSettings,
  SalonHolidays,
  TeamCategory,
  TeamMember,
  TeamMemberList,
  TeamService,
  TeamSignInAccount,
  TeamSignInAccountList,
} from './types';
export type {
  AddedDateChange,
  MemberDateChange,
  MemberSchedule,
  SalonOpeningWeek,
  SavedMemberWeek,
  TeamAffectedBooking,
  Weekday,
} from './types';
export { AffectedBookings } from './components/AffectedBookings';
export { DateChangesTab, type ChangeRefusal } from './components/DateChangesTab';
export { ScheduleSheet, type ScheduleTab } from './components/ScheduleSheet';
export { WeeklyHoursTab } from './components/WeeklyHoursTab';
export {
  changeIsEdited,
  changeSaveOf,
  copyMonday,
  newChange,
  problemOfField,
  problemsOfWeekFields,
  salonHoursOn,
  sameWeek as sameWorkWeek,
  weekFormOf as workWeekFormOf,
  weekProblems,
  weekSaveOf as workWeekSaveOf,
  withChange,
  type ChangeForm,
  type HoursProblem,
  type WeekProblem,
  type WorkdayForm,
} from './client/schedule-form';
export { ConfirmRemoveMember } from './components/ConfirmRemoveMember';
export type { MemberPhotoUpload } from './components/MemberPhotoField';
export { MemberSheet } from './components/MemberSheet';
export { TeamCards } from './components/TeamCards';
export {
  newMember,
  problemsOf as memberProblemsOf,
  problemsOfFields as memberProblemsOfFields,
  requestOf as memberRequestOf,
  sameValues as sameMemberValues,
  valuesOf as memberValuesOf,
  type MemberProblem,
  type MemberValues,
} from './client/team-form';
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
