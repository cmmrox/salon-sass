/**
 * F07 browser fixtures: the test with the operator and the test clock, opening a workspace page signed in, a support
 * session's browser, and the names S02's Booking rules section and S03's screens give their controls.
 *
 * The words come from the F07-S02 and F07-S03 specs and the prototype's F07 block (docs/design/prototype/index.html:
 * `setRules`, `aApptsW`, `aAppts`, `f7Tools`, `f7New`, `f7Find`, `f7Hold`, `f7Confirm`, `f7Expire`, `openA`, `f7Set`,
 * `f7Cancel`), written here rather than read from the product's translation files. The test ids are the specs'
 * requirement ("every interactive element has a data-testid") with names chosen to the workspace's convention; they
 * are confirmed, or corrected here only, against the S02 and S03 handoffs when those stages close. A control the spec
 * names is found by its role and accessible name instead wherever it can be.
 */
import type { Browser, BrowserContext, Locator, Page } from '@playwright/test';

import { webUrl } from './env';
import { test as browserTest, expect, type AccountBrowser } from './identity-browser';
import type { Actor } from './identity';
import { Operator, TestClock, supportJson, type SupportStart } from './operator';
import { salonUrl, type Salon } from './tenants';

export const test = browserTest.extend<{ operator: Operator; clock: TestClock }>({
  operator: async ({ identity }, use) => {
    const operator = new Operator(identity);
    await operator.resetFixtures();
    try { await use(operator); } finally { await operator.resetToBaseline(); }
  },
  clock: async ({ identity }, use) => {
    const clock = new TestClock(identity);
    try { await use(clock); } finally { await clock.real(); }
  },
});
export { expect };

export const id = (scope: Page | Locator, name: string) => scope.getByTestId(name).filter({ visible: true });
export const button = (scope: Page | Locator, name: string | RegExp, exact = false) => scope.getByRole('button', { name, exact }).filter({ visible: true });

export async function settled(page: Page) {
  await page.waitForFunction(() => document.getAnimations().every(a => a.playState !== 'running'));
}

/**
 * The browser's clock set to the application's: every F07 test moves the test clock (to a date after every plan), and a
 * calendar or countdown that reads the browser's date would otherwise show another month. Time runs on from there.
 */
export async function alignClock(context: BrowserContext, identity: { now(): Promise<string> }) {
  await context.clock.setSystemTime(new Date(await identity.now()));
}

/** A workspace page, signed in as the actor, at a width, its clock the application's. */
export async function openAs(accounts: AccountBrowser, actor: Actor, path: string, width = 1280): Promise<{ context: BrowserContext; page: Page }> {
  const context = await accounts.context(actor.salon, [actor], { width });
  await alignClock(context, accounts.identity);
  const page = await accounts.open(context, path);
  return { context, page };
}

/** A support session's browser on the salon's own web address. */
export async function supportBrowser(browser: Browser, operator: Operator, created: { id: string }, salon: Salon, width = 1280) {
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'The salon asked about a booking on the list' }), 'supportStart', 201);
  const context = await browser.newContext({ baseURL: salonUrl(salon, webUrl), viewport: { width, height: 1000 }, locale: 'en' });
  await alignClock(context, operator.identity);
  const entry = await context.newPage();
  await entry.goto(`${salonUrl(salon, webUrl)}/workspace/support#${session.entryCode}`);
  await expect(entry.getByTestId('support-banner')).toBeVisible();
  return context;
}

// ---------------------------------------------------------------- F07-S02: Settings, Booking rules

export const RULES_ROUTE = '/workspace/settings/rules';
/** Confirmed against the S02 handoff (note 15) at the dry run (17c41afa). */
export const PENDING_F07_S02_WEB = {
  hold: 'rules-hold',
  holdHelp: 'rules-hold-help',
  noPreference: 'rules-no-preference',
  noPreferenceHelp: 'rules-no-preference-help',
  /** F04's convention: `<trigger>-option-<value>`. */
  option: (trigger: string, value: string) => `${trigger}-option-${value}`,
} as const;
export const S02_TEXT = {
  heading: 'Holding and assigning',
  before: 'Booking times',
  after: 'Deposit',
  holdLabel: 'Hold a chosen time for',
  holdHelp: 'While a time is held nobody else can take it. It is released by itself afterwards, unless a payment is in progress.',
  holdOptions: [5, 10, 15, 20, 30].map(n => [String(n), `${n} minutes`] as [string, string]),
  whoLabel: '“No preference” bookings go to',
  whoHelp: 'The first shares work evenly. The second fills one person’s day first and keeps others free for walk-ins. Only people who do the service and are free are considered.',
  whoOptions: [['least_booked', 'Whoever has the least booked that day'], ['most_booked', 'Whoever has the most booked that day']] as [string, string][],
  saved: 'Booking rules saved. They apply to new bookings.',
} as const;

// ---------------------------------------------------------------- F07-S03: Bookings

export const BOOKINGS_ROUTE = '/workspace/bookings';
/** S03's test ids and URL parameters, confirmed against its handoff ("TEST IDS", the address) at feature-verify (76e3b080). */
export const PENDING_F07_S03 = {
  nav: 'workspace-bookings',
  page: {
    page: 'bookings-page', heading: 'bookings-heading', loading: 'bookings-loading', empty: 'bookings-empty', error: 'bookings-error',
    unavailable: 'bookings-unavailable', retry: 'bookings-retry', notPermitted: 'account-denied', readOnly: 'bookings-read-only',
    table: 'bookings-table', items: 'bookings-items', row: 'booking-row', item: 'booking-item', open: 'booking-open',
    search: 'bookings-search', day: 'bookings-day', dayToday: 'bookings-day-today', calendar: 'bookings-calendar',
    status: 'bookings-status', statusOption: (v: string) => `bookings-status-option-${v}`,
    professional: 'bookings-professional', professionalAll: 'bookings-professional-option-all', professionalOption: 'bookings-professional-option',
    clear: 'bookings-clear', pager: 'bookings-pager', next: 'bookings-next', previous: 'bookings-previous', pageNumber: 'bookings-page',
    statusBadge: 'booking-status', source: 'booking-source', reference: 'booking-reference', when: 'booking-when', mobile: 'booking-mobile',
    newAppointment: 'bookings-new', emptyNew: 'bookings-empty-new',
  },
  sheet: {
    sheet: 'new-appointment', close: 'new-appointment-close', intro: 'new-appointment-intro', announcement: 'new-appointment-announcement',
    name: 'new-appointment-name', nameError: 'new-appointment-name-error', mobile: 'new-appointment-mobile', mobileError: 'new-appointment-mobile-error',
    day: 'new-appointment-day', calendar: 'new-appointment-calendar', service: 'new-appointment-service', serviceOption: 'new-appointment-service-option',
    variant: 'new-appointment-variant', variantOption: 'new-appointment-variant-option',
    professional: 'new-appointment-professional', professionalAny: 'new-appointment-professional-option-any', professionalOption: 'new-appointment-professional-option',
    source: 'new-appointment-source', sourceOption: (v: string) => `new-appointment-source-option-${v}`, find: 'new-appointment-find',
    times: 'new-appointment-times', time: 'new-appointment-time', timesIntro: 'new-appointment-times-intro', noPreference: 'new-appointment-no-preference',
    reason: 'new-appointment-reason', refusal: 'new-appointment-refusal', rulesLink: 'new-appointment-rules-link', hold: 'new-appointment-hold',
    holding: 'new-appointment-holding', countdown: 'new-appointment-countdown', holdNote: 'new-appointment-hold-note',
    confirm: 'new-appointment-confirm', another: 'new-appointment-another',
    leaveDialog: 'new-appointment-leave-dialog', leaveStay: 'new-appointment-leave-stay', leaveConfirm: 'new-appointment-leave-confirm',
  },
  appointment: {
    sheet: 'appointment-sheet', close: 'appointment-close', name: 'appointment-name', status: 'appointment-status', source: 'appointment-source',
    rows: 'appointment-row', history: 'appointment-history', historyRow: 'appointment-history-row', historyBy: 'appointment-history-by',
    historyWhy: 'appointment-history-why', explanation: 'appointment-explanation', refusal: 'appointment-refusal', loading: 'appointment-loading',
    action: (a: string) => `appointment-${a}`,
  },
  cancel: { dialog: 'cancel-dialog', summary: 'cancel-summary', reason: 'cancel-reason', reasonOption: (v: string) => `cancel-reason-option-${v}`,
    reasonError: 'cancel-reason-error', note: 'cancel-note', confirm: 'cancel-confirm', keep: 'cancel-keep' },
  /** The page's address (handoff): page counts from 1. */
  query: { day: 'day', q: 'q', status: 'status', professional: 'professional', page: 'page', booking: 'booking' },
  statusOptions: ['all', 'held', 'confirmed', 'arrived', 'in_service', 'completed', 'cancelled', 'no_show'],
  sourceOptions: ['phone', 'whatsapp', 'front_desk', 'walk_in', 'instagram', 'google'],
} as const;

/** The words of the S03 spec (AC-2 to AC-9), as the prototype writes them. */
export const S03_TEXT = {
  nav: 'Bookings',
  heading: (n: number, day: string) => `${n} booking${n === 1 ? '' : 's'} on ${day}`,
  columns: ['When', 'Customer', 'Service', 'Professional', 'Status', 'Reference'],
  held: (left: string) => `Held · ${left} left`,
  statusLabels: { held: 'Held', confirmed: 'Confirmed', arrived: 'Arrived', in_service: 'In service', completed: 'Completed', cancelled: 'Cancelled', no_show: 'No-show' } as Record<string, string>,
  statusOptionLabels: ['All statuses', 'Held', 'Confirmed', 'Arrived', 'In service', 'Completed', 'Cancelled', 'No-show'],
  everyone: 'Everyone',
  emptyTitle: 'No bookings match.',
  emptyHint: 'Try another day, or clear the search.',
  clear: 'Clear filters',
  newAppointment: '+ New appointment',
  sheetIntro: 'For phone calls, messages and front-desk bookings on any open day.',
  name: 'Customer name',
  mobile: 'Mobile number',
  mobileHelp: 'Required. The confirmation goes to this number.',
  day: 'Day', service: 'Service', professional: 'Professional', noPreference: 'No preference',
  source: 'How did they book?',
  sourceLabels: ['Phone', 'WhatsApp', 'Front desk', 'Walk-in', 'Instagram', 'Google'],
  find: 'Find free times',
  nameMissing: 'Add the customer’s name.',
  mobileMissing: 'Add a valid mobile number so the customer gets the confirmation.',
  timesIntro: (day: string) => `Free times on ${day}. Choose one to hold it.`,
  /** One line for every role and strategy (the S03 spec as changed before build, 6705c113; decision 111). */
  noPreferenceLine: 'Each time shows who would take it.',
  /** Retired by that change; a screen must no longer show them. */
  retiredStrategyLines: ['sharing bookings evenly', 'filling the busiest day first'],
  chip: (time: string, name: string) => `${time} · ${name}`,
  closed: (day: string) => `The salon is closed on ${day}.`,
  nobodyPerforms: 'Nobody on the team does this service.',
  notWorking: (name: string, day: string) => `${name} isn’t working on ${day}.`,
  nobodyWorking: (day: string) => `Nobody who does this service is working on ${day}.`,
  fullyBooked: (day: string) => `No free time on ${day} for this service. Try another day or professional.`,
  holding: 'Holding this time',
  holdNote: 'Nobody else can take this time while it is held. It is released by itself when the time runs out.',
  confirm: 'Confirm booking',
  another: 'Choose another time',
  booked: (day: string, time: string, first: string, reference: string) => `Booked for ${day} at ${time} with ${first}. Reference ${reference}.`,
  taken: 'That time was just taken. Choose another.',
  ranOut: 'The hold ran out and the time was released. Find free times again.',
  rows: ['Reference', 'Mobile', 'Service', 'Professional', 'Date', 'Time', 'Source'],
  actions: { arrive: 'Mark arrived', start: 'Start service', 'no-show': 'Mark no-show', 'undo-no-show': 'Undo no-show', cancel: 'Cancel booking', release: 'Release hold' } as Record<string, string>,
  done: { arrive: 'Marked arrived.', start: 'Service started.', 'no-show': (first: string) => `${first} marked no-show.`, 'undo-no-show': 'No-show undone.', release: 'Hold released. The time is open again.' },
  explanations: {
    arriveOnDay: 'Arrival is marked on the day.',
    noShowAfterStart: 'You can mark a no-show once the start time has passed.',
    noCancelAfterArrival: 'Cancel and no-show are no longer possible once the customer has arrived.',
    completedAtCheckout: 'The booking is completed at checkout, which arrives with the front desk.',
    undoToday: 'A no-show can be undone today only.',
    cancelledFinal: 'Cancelled bookings can’t be changed. Make a new booking instead.',
  },
  /** History's "who": the staff label for every role, "A team member" for a staff actor with no label, or "System"; never "You" (6705c113). */
  history: 'History', teamMember: 'A team member', system: 'System', notYou: 'You',
  /** History's verbs, the prototype's (S03 handoff, choice 4). */
  verbs: { held: 'Held', confirmed: 'Confirmed', arrived: 'Marked arrived', in_service: 'Service started', no_show: 'Marked no-show', undone: 'No-show undone', cancelled: 'Cancelled' } as Record<string, string>,
  /**
   * The navigation's order around the new entry (decision 111 c: after Account, before Team). The entries read "Account
   * and security" and "Staff" (Staff opens the team's people), as the S03 handoff's walk names them.
   */
  navAfter: 'Account and security', navBefore: 'Staff',
  cancelTitle: 'Cancel this booking?',
  cancelOpens: 'The time opens again for others.',
  cancelReason: 'Reason',
  cancelReasons: [['customer_asked', 'Customer asked to cancel'], ['salon_unable', 'The salon can’t do it'], ['booked_by_mistake', 'Booked by mistake'], ['other', 'Other']] as [string, string][],
  cancelNote: 'Note', cancelNoteHint: 'your team only', cancelConfirm: 'Cancel booking', keep: 'Keep booking',
  cancelled: 'Cancelled. The time is open again.',
  changed: 'This booking changed. Here is its current state.',
  rulesNotSet: 'Set your booking rules in Settings before taking bookings.',
} as const;

/** Every width the design standards name for the workspace. */
export { DESIGN_WIDTHS } from './design-reference';

/**
 * How S03 writes a time and a day (its AC-12, decisions 103, 106 and 107): the existing 24-hour `formatClock` and the
 * existing `formatDay` (en-LK: weekday, day, month and year). Written here from those documented formats, so a change
 * to the product's formatter shows as a difference. The prototype's "10:30 AM" is its 12-hour clock, which decision 103
 * leaves to its own request.
 */
export const shownTime = (hhmm: string) => hhmm;
export const shownDay = (date: string) => new Intl.DateTimeFormat('en-LK', { timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
  .format(new Date(`${date}T00:00:00Z`));
export const firstName = (name: string) => name.split(' ')[0];
/** `m:ss` left until an instant, by the application's clock. */
export const left = (until: string, now: string) => { const s = Math.max(0, Math.ceil((Date.parse(until) - Date.parse(now)) / 1000)); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

/** A day's button in a shadcn Calendar by its accessible name, as F04's hours screens find it ("Thursday, 12 November 2026"). */
export function calendarDayButton(scope: Locator, date: string): Locator {
  const written = new Intl.DateTimeFormat('en-LK', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' })
    .format(new Date(`${date}T00:00:00Z`));
  return scope.getByRole('button', { name: new RegExp(`^(Today, )?${written}(, selected)?$`) });
}
/** Chooses a day in an open Calendar, moving a month on at most twelve times until it shows. */
export async function chooseCalendarDay(calendar: Locator, date: string) {
  const day = calendarDayButton(calendar, date);
  for (let n = 0; n < 12 && !(await day.count()); n++) await calendar.getByRole('button', { name: /next month/i }).click();
  await day.click();
}
