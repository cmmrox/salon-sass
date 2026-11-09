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

/** A workspace page, signed in as the actor, at a width. */
export async function openAs(accounts: AccountBrowser, actor: Actor, path: string, width = 1280): Promise<{ context: BrowserContext; page: Page }> {
  const context = await accounts.context(actor.salon, [actor], { width });
  const page = await accounts.open(context, path);
  return { context, page };
}

/** A support session's browser on the salon's own web address. */
export async function supportBrowser(browser: Browser, operator: Operator, created: { id: string }, salon: Salon, width = 1280) {
  const support = await operator.actor('OPERATOR_SUPPORT');
  const session = await supportJson<SupportStart>(await operator.startSupport(support, created.id, { reason: 'The salon asked about a booking on the list' }), 'supportStart', 201);
  const context = await browser.newContext({ baseURL: salonUrl(salon, webUrl), viewport: { width, height: 1000 }, locale: 'en' });
  const entry = await context.newPage();
  await entry.goto(`${salonUrl(salon, webUrl)}/workspace/support#${session.entryCode}`);
  await expect(entry.getByTestId('support-banner')).toBeVisible();
  return context;
}

// ---------------------------------------------------------------- F07-S02: Settings, Booking rules

export const RULES_ROUTE = '/workspace/settings/rules';
export const PENDING_F07_S02_WEB = {
  hold: 'rules-hold',
  noPreference: 'rules-no-preference',
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
export const PENDING_F07_S03 = {
  nav: 'nav-bookings',
  page: {
    heading: 'bookings-heading', loading: 'bookings-loading', empty: 'bookings-empty', error: 'bookings-error', unavailable: 'bookings-unavailable',
    retry: 'bookings-retry', notPermitted: 'workspace-not-permitted', table: 'bookings-table', row: 'booking-row', item: 'booking-item',
    search: 'bookings-search', day: 'bookings-day', dayToday: 'bookings-day-today', calendar: 'bookings-calendar', status: 'bookings-status',
    professional: 'bookings-professional', clear: 'bookings-clear', pager: 'bookings-pager', next: 'bookings-next', previous: 'bookings-previous',
    statusBadge: 'booking-status', reference: 'booking-reference', newAppointment: 'bookings-new',
  },
  sheet: {
    sheet: 'new-appointment', close: 'new-appointment-close', name: 'new-appointment-name', mobile: 'new-appointment-mobile',
    day: 'new-appointment-day', calendar: 'new-appointment-calendar', service: 'new-appointment-service', variant: 'new-appointment-variant',
    professional: 'new-appointment-professional', source: 'new-appointment-source', find: 'new-appointment-find', times: 'new-appointment-times',
    time: 'new-appointment-time', timesIntro: 'new-appointment-times-intro', reason: 'new-appointment-reason', hold: 'new-appointment-hold',
    countdown: 'new-appointment-countdown', confirm: 'new-appointment-confirm', another: 'new-appointment-another', refusal: 'new-appointment-refusal',
    leaveDialog: 'new-appointment-leave', leaveStay: 'new-appointment-leave-stay', leaveConfirm: 'new-appointment-leave-confirm',
  },
  appointment: {
    sheet: 'appointment-sheet', close: 'appointment-close', name: 'appointment-name', status: 'appointment-status', source: 'appointment-source',
    rows: 'appointment-row', history: 'appointment-history', historyRow: 'appointment-history-row', explanation: 'appointment-explanation',
    refusal: 'appointment-refusal',
    action: (a: string) => `appointment-${a}`,
  },
  cancel: { dialog: 'cancel-dialog', reason: 'cancel-reason', note: 'cancel-note', confirm: 'cancel-confirm', keep: 'cancel-keep' },
  /** The URL's names for the day and the search (AC-3 says the URL keeps them; the names are the build's). */
  query: { day: 'day', q: 'q' },
  /** The status Select's values, in the spec's order (AC-3). */
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
  noPreferenceLeast: 'Each time shows who would take it, sharing bookings evenly.',
  noPreferenceMost: 'Each time shows who would take it, filling the busiest day first.',
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
  history: 'History', you: 'You', system: 'System',
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
