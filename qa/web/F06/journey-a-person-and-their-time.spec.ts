/**
 * F06's journey across its three stages, from the README's "Feature-level test focus": an Owner adds a person with no
 * sign-in through the screens, assigns services, sets their week and a half day, adds leave and a block; the card, the
 * schedule and the public team API then say exactly that, and each reader receives only what is theirs.
 *
 * It asserts what passes between the stages, not each stage's rules, which are the stages' cases. That "the working
 * windows then say exactly that" needs `TeamSchedule`, a Java interface with no route until F07 (deferred TC-F06-004).
 * Prepared before F06-S03 closed: its names are in `PENDING_F06_S03`.
 */
import type { Locator, Page } from '@playwright/test';

import { Catalogue, serviceInput } from '../../shared/catalogue';
import { expect } from '../../shared/identity-browser';
import { Hours, plusDays, week } from '../../shared/salon-hours';
import { teamSalon, type TeamMember } from '../../shared/team';
import { button, card, id, open, openSchedule, ready, salonToday, settled, sheetOf, test } from '../../shared/team-browser';
import {
  ABSENCE_TEXT, Absences, LEAVE_KEYS_WITHOUT_MANAGE, PENDING_F06_S03, TYPE_WORDS, pickDay, shortDaysWords, type MemberWithLeave,
} from '../../shared/team-leave';
import { LONG, PENDING_F06_S02, SCHEDULE_TEXT, copyOf, expectWeek, shownTime, workingDay } from '../../shared/team-schedule';

test.describe.configure({ timeout: 240_000 });

const L = PENDING_F06_S03.web;
const LN = PENDING_F06_S03.names;
const S = PENDING_F06_S02.web;
const SN = PENDING_F06_S02.names;

async function setNamedTime(page: Page, sheet: Locator, name: string, hhmm: string) {
  await sheet.getByRole('combobox', { name, exact: true }).click();
  await page.getByRole('option', { name: shownTime(hhmm), exact: true }).click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}
async function setTime(page: Page, sheet: Locator, trigger: string, hhmm: string) {
  await id(sheet, trigger).click();
  await page.getByTestId(`${trigger}-option-${hhmm.replace(':', '')}`).click();
  await expect(page.getByRole('listbox')).toHaveCount(0);
}
const sheetAgain = async (page: Page, memberId: string) => {
  await settled(page);
  return (await sheetOf(page).count()) > 0 ? sheetOf(page) : openSchedule(page, memberId);
};

test('TC-F06-001 STF-01, STF-02, STF-03, STF-05: an Owner adds a person with no sign-in, assigns services, sets their week and a half day, adds sick leave and a block; the card, the schedule and the public team API then say exactly that, and a Receptionist receives only the leave\'s days', async ({ accounts, operator, team, schedules, hours }) => {
  const { salon, owner } = await teamSalon(operator, accounts.identity);
  const cat = new Catalogue(accounts.identity);
  const opening = week('09:00', '18:00', ['sunday']);
  await hours.save(salon, owner.tokens, opening);
  const colour = await cat.category(salon, owner.tokens, 'Colour');
  const balayage = await cat.create(salon, owner.tokens, serviceInput(colour.id, { name: 'Balayage' }));
  const today = await salonToday(accounts);
  const absences = new Absences(team);
  const { page } = await open(accounts, owner);

  let person!: TeamMember;
  await test.step('a person with no sign-in and a whole category, through the profile sheet', async () => {
    await id(page, 'team-empty-add').click();
    await id(page, 'team-name').fill('Sanduni Perera');
    await page.locator(`[data-testid="team-picker-row"][data-id="${colour.id}"]`).filter({ visible: true }).getByTestId('team-picker-category').click();
    await id(page, 'team-sheet-save').click();
    await expect(id(page, 'team-sheet')).toHaveCount(0);
    person = (await team.list(salon, owner.tokens)).find(m => m.name === 'Sanduni Perera')!;
    expect(person.signInAccountId ?? null, 'bookable with no sign-in').toBeNull();
    expectWeek(person.weekly, copyOf(opening), 'S02 reads S01\'s new person: they start on the salon\'s hours');
    await expect(id(card(page, person.id), S.cardHours)).toHaveText(SCHEDULE_TEXT.row.stretch('monday', 'saturday', '09:00', '18:00'));
  });

  const halfDay = plusDays(today, 4);
  const [leaveFirst, leaveLast] = [plusDays(today, 10), plusDays(today, 12)];
  const blockDate = plusDays(today, 5);
  await test.step('their week and a half day', async () => {
    const sheet = await openSchedule(page, person.id);
    await setNamedTime(page, sheet, SN.finishes(LONG.monday), '17:00');
    await button(sheet, 'Save hours').click();
    await expect(page.getByText(SCHEDULE_TEXT.saved).filter({ visible: true })).toBeVisible();
    const again = await sheetAgain(page, person.id);
    await again.getByRole('tab', { name: 'Date changes' }).click();
    await button(again, '+ Change a date').click();
    await pickDay(page, id(again, S.changeDate), S.changeCalendar, halfDay);
    await setNamedTime(page, again, SN.changeStarts, '09:00');
    await setNamedTime(page, again, SN.changeFinishes, '13:00');
    await button(again, SN.saveChange).click();
    await expect(again.getByTestId(S.changeRow).filter({ visible: true })).toHaveCount(1);
  });
  await test.step('sick leave and a block', async () => {
    const sheet = sheetOf(page);
    await sheet.getByRole('tab', { name: LN.leaveTab, exact: true }).click();
    await button(sheet, LN.addLeave, true).click();
    await pickDay(page, id(sheet, L.leaveFirst), L.leaveFirstCalendar, leaveFirst);
    await pickDay(page, id(sheet, L.leaveLast), L.leaveLastCalendar, leaveLast);
    await id(sheet, L.leaveType).click();
    await page.getByRole('option', { name: TYPE_WORDS.sick, exact: true }).click();
    await id(sheet, L.leaveNote).fill('Private note');
    await button(sheet, LN.saveLeave, true).click();
    await expect(page.getByText(ABSENCE_TEXT.leaveAdded, { exact: true }).filter({ visible: true })).toBeVisible();
    await sheet.getByRole('tab', { name: LN.blockTab, exact: true }).click();
    await button(sheet, LN.addBlock, true).click();
    await pickDay(page, id(sheet, L.blockDate), L.blockCalendar, blockDate);
    await setTime(page, sheet, L.blockStarts, '10:00');
    await setTime(page, sheet, L.blockFinishes, '11:00');
    await id(sheet, L.blockLabel).fill('Training');
    await button(sheet, LN.saveBlock, true).click();
    await expect(page.getByText(ABSENCE_TEXT.blocked, { exact: true }).filter({ visible: true })).toBeVisible();
    await id(sheet, S.close).click();
    await expect(sheetOf(page)).toHaveCount(0);
  });

  await test.step('the card says it', async () => {
    await page.reload();
    await ready(page);
    await expect(id(card(page, person.id), S.cardHours), 'the week, which no date change or leave moves').toHaveText(SCHEDULE_TEXT.row.varies);
    // The short day form of decision 107 (CR-026).
    await expect(id(card(page, person.id), L.cardLeave)).toHaveText(`${TYPE_WORDS.sick}, ${shortDaysWords(leaveFirst, leaveLast, today)}`);
  });
  await test.step('the schedule says exactly that', async () => {
    const read = await absences.read(salon, owner.tokens, person.id);
    expectWeek(read.weekly, copyOf(opening).map(d => (d.day === 'monday' ? workingDay('monday', '09:00', '17:00') : d)), 'the week');
    expect(read.dateChanges.map(c => [c.date, c.starts, c.finishes]), 'the half day').toEqual([[halfDay, '09:00', '13:00']]);
    expect(read.leave.map(l => [l.firstDay, l.lastDay, l.type, l.note]), 'the leave').toEqual([[leaveFirst, leaveLast, 'sick', 'Private note']]);
    expect(read.blockedTime.map(b => [b.date, b.starts, b.finishes, b.label]), 'the block').toEqual([[blockDate, '10:00', '11:00', 'Training']]);
    expect((await schedules.read(salon, owner.tokens, person.id)).version, 'one weekly save').toBeGreaterThan(0);
  });
  await test.step('the public team API shows the person and their service, and nothing of their time', async () => {
    const answer = await team.publicRaw(salon.hostname);
    const body = await answer.text();
    const items = (JSON.parse(body) as { items: { id: string; serviceIds: string[] }[] }).items;
    expect(items.map(i => [i.id, i.serviceIds]), 'bookable for the whole category').toEqual([[person.id, [balayage.id]]]);
    for (const word of ['weekly', 'dateChanges', 'leave', 'blockedTime', 'sick', 'Private note', 'Training', '13:00']) expect(body.toLowerCase(), `no trace of "${word}"`).not.toContain(word.toLowerCase());
  });
  await test.step('a Receptionist receives the leave\'s days alone', async () => {
    const receptionist = await accounts.identity.staff(salon, 'RECEPTIONIST');
    const read = await absences.read(salon, receptionist.tokens, person.id);
    expect(Object.keys(read.leave[0]).sort()).toEqual(LEAVE_KEYS_WITHOUT_MANAGE);
    expect((await team.get(salon, receptionist.tokens, person.id) as MemberWithLeave).nextLeave).toEqual({ firstDay: leaveFirst, lastDay: leaveLast });
  });
  await cat.dispose();
});
