/**
 * F06-S01 staff seats and everything two people can do at once: AC-6's seat rule from outside, the
 * count across link, unlink, account removal and member removal, the operator's plan move, a salon
 * without a plan, and the races the spec calls the stage's highest risk: the last seat fought over by
 * adding a member and creating an account in both orders, unlinking in the same fight, a plan move
 * during it, a storm of every seat-changing action to prove no deadlock between identity's lock and
 * staff's rows, two saves linking one account, an account removed while it is being linked, and two
 * saves of one version.
 *
 * Expected results are the F06-S01 spec's (AC-2, AC-5, AC-6, "Corrections at close", "Risks and test
 * focus") and decisions 099 to 101. Every race asserts the invariant rather than the responses one by
 * one, and reads the end state back row by row.
 *
 * Sent in the same tick, the request with the shorter path to the lock always wins: at the dry run an
 * account beat a person 16 times in 16, and an account's removal beat a link every time, so one order was
 * never fought and a defect in it could not have shown. So a race between two different requests sweeps a
 * small gap between them (`GAPS`) in both orders, and asserts that each side won at least once.
 */
import type { APIResponse } from '@playwright/test';

import { refused } from '../../shared/booking-rules';
import type { Identity, SignedStaff } from '../../shared/identity';
import { test, expect, fieldsOf, operatorJson, operatorProblem } from '../../shared/operator';
import { GAPS, Team, deadlocks, fire, memberInput, noSeat, refusedOn, teamSalon, teamSnapshot, updateOf, type TeamMember } from '../../shared/team';

test.describe.configure({ timeout: 180_000 });

const status = (r: APIResponse) => r.status();
const worked = (r: APIResponse) => r.status() >= 200 && r.status() < 300;

/** Who is not on the team or not active any more, for reading a race's end state. */
async function activeAccountIds(identity: Identity, owner: SignedStaff) {
  return new Set((await identity.list(owner)).filter(a => a.active).map(a => a.id));
}

// ---------------------------------------------------------------- the rule, one request at a time

test('TC-F06-S01-027 AC-6: each action takes, frees or needs no seat as the rule says: with no free seat adding a person or creating an account is 409 entitlement_exceeded naming staffSeats and changes nothing, while linking, adding someone linked and editing still work', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner, created } = await teamSalon(operator, identity, 3);
  await team.expectSeats(owner, 1, 'the Owner alone');
  const free = await team.createAccount(salon, owner.tokens);
  let m1 = await team.add(salon, owner.tokens);
  await team.expectSeats(owner, 3, 'the Owner, an account nobody is linked to, and a person with no sign-in: full');

  await test.step('no free seat: a new person and a new account are refused, changing nothing', async () => {
    const before = teamSnapshot(created.id);
    await noSeat(await team.addRaw(salon, owner.tokens, memberInput()), 'adding a person with no sign-in');
    await noSeat(await team.createAccountRaw(salon, owner.tokens), 'creating an account');
    expect(teamSnapshot(created.id), 'no member stored').toBe(before);
    await team.expectSeats(owner, 3, 'after the refusals');
  });
  await test.step('no seat is needed to edit a person without changing the link', async () => {
    m1 = await team.save(salon, owner.tokens, m1, { title: 'Senior stylist' });
    await team.expectSeats(owner, 3, 'after the edit');
  });
  await test.step('linking a person with no sign-in to a free account frees a seat', async () => {
    m1 = await team.link(salon, owner.tokens, m1, free.id);
    await team.expectSeats(owner, 2, 'one person now counts once');
  });
  const m2 = await team.add(salon, owner.tokens);
  await team.expectSeats(owner, 3, 'the freed seat is usable');
  await test.step('unlinking a person whose account stays needs a free seat', async () => {
    const before = teamSnapshot(created.id);
    await noSeat(await team.saveRaw(salon, owner.tokens, m1.id, updateOf(m1, {}, null)), 'unlinking with no free seat');
    expect(teamSnapshot(created.id), 'still linked').toBe(before);
    expect((await team.get(salon, owner.tokens, m1.id)).signInAccountId).toBe(free.id);
  });
  const onOwner = await test.step('adding a person linked to a free account needs no seat', async () => {
    const linked = await team.add(salon, owner.tokens, memberInput({ signInAccountId: owner.principal.id }));
    expect(linked.signInAccountId).toBe(owner.principal.id);
    await team.expectSeats(owner, 3, 'the Owner\'s account now has a person, still three');
    return linked;
  });
  await test.step('with a free seat, unlinking takes it', async () => {
    await team.remove(salon, owner.tokens, m2);
    await team.expectSeats(owner, 2, 'removing a person with no sign-in frees a seat');
    m1 = await team.link(salon, owner.tokens, m1, null);
    await team.expectSeats(owner, 3, 'the account and the person now count apart');
  });
  await test.step('moving a person from one account to a free one changes no count, so it needs no free seat', async () => {
    const moved = await team.link(salon, owner.tokens, onOwner, free.id);
    expect(moved.signInAccountId, 'on the other account').toBe(free.id);
    await team.expectSeats(owner, 3, 'still full, the same people');
  });
  await team.dispose();
});

test('TC-F06-S01-028 AC-6: the count stays one per person across removals: removing a linked person leaves the account\'s seat, removing an account with a person leaves the person\'s seat even at the limit, and a removed person stops counting', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner, created } = await teamSalon(operator, identity, 3);
  const account = await team.createAccount(salon, owner.tokens);
  const linked = await team.add(salon, owner.tokens, memberInput({ signInAccountId: account.id }));
  const unlinked = await team.add(salon, owner.tokens);
  await team.expectSeats(owner, 3, 'the Owner, the linked pair once, and a person with no sign-in: full');

  await test.step('removing the account at the limit: the person stays, and takes the account\'s seat', async () => {
    await team.removeAccount(salon, owner.tokens, account.id);
    const still = await team.get(salon, owner.tokens, linked.id);
    expect(still.signInAccountId ?? null, 'on the team with no sign-in').toBeNull();
    await team.expectSeats(owner, 3, 'one person left the accounts and joined the unlinked');
  });
  await test.step('removing a person with no sign-in frees a seat', async () => {
    await team.remove(salon, owner.tokens, unlinked);
    await team.expectSeats(owner, 2, 'one fewer');
  });
  await test.step('removing a linked person leaves their account and its seat, free to link', async () => {
    const second = await team.createAccount(salon, owner.tokens);
    const person = await team.get(salon, owner.tokens, linked.id);
    const relinked = await team.link(salon, owner.tokens, person, second.id);
    await team.expectSeats(owner, 2, 'the Owner and the linked pair');
    await team.remove(salon, owner.tokens, relinked);
    await team.expectSeats(owner, 2, 'the account still counts');
    expect((await identity.list(owner)).find(a => a.id === second.id)?.active, 'the account stays active').toBe(true);
    const again = await team.add(salon, owner.tokens, memberInput({ signInAccountId: second.id }));
    expect(again.signInAccountId, 'free to link again').toBe(second.id);
    await team.expectSeats(owner, 2, 'and still once');
  });
  expect(created.id).toBeTruthy();
  await team.dispose();
});

test('TC-F06-S01-029 AC-6: the operator\'s plan move counts members: a plan below the seats used is 409 entitlement_exceeded naming staffSeats and the plan stays, a plan of exactly the seats used is accepted, and a linked pair counts once', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { admin, salon, owner, created, plan } = await teamSalon(operator, identity, 5);
  const two = await operator.plan(admin, { staffSeats: 2 });
  const three = await operator.plan(admin, { staffSeats: 3 });
  const account = await team.createAccount(salon, owner.tokens);
  await team.add(salon, owner.tokens, memberInput({ signInAccountId: account.id }));
  await team.add(salon, owner.tokens);
  await team.expectSeats(owner, 3, 'the Owner, a linked pair once, a person with no sign-in');
  await test.step('a plan of two seats is below what is used, only because the person with no sign-in counts', async () => {
    const version = await operator.version(admin, created.id);
    const r = await operatorProblem(await operator.movePlan(admin, created.id, version, two.id), 409, 'entitlement_exceeded');
    expect(fieldsOf(r), 'names the limit').toContain('staffSeats');
    expect((await operator.salonDetail(admin, created.id)).plan?.id, 'the plan stays').toBe(plan.id);
  });
  await test.step('a plan of three seats fits exactly: the linked pair is not counted twice', async () => {
    const version = await operator.version(admin, created.id);
    await operatorJson(await operator.movePlan(admin, created.id, version, three.id), 'salon');
    expect((await operator.salonDetail(admin, created.id)).plan?.id, 'moved').toBe(three.id);
    const entitlements = await team.entitlements(salon, owner.tokens);
    expect(entitlements.limits?.staffSeats, 'the new limit').toBe(3);
    expect(entitlements.usage.staffSeats, 'the same count on the salon\'s side').toBe(3);
    await noSeat(await team.addRaw(salon, owner.tokens, memberInput()), 'the new limit holds');
  });
  await team.dispose();
});

test('TC-F06-S01-030 AC-6: a salon without a plan is not limited: people and accounts are added past any plan\'s seats', async ({ identity, operator }) => {
  const team = new Team(identity);
  const admin = await operator.actor();
  const legacy = await operator.unbilledSalon();
  expect((await operator.salonDetail(admin, legacy.id)).plan, 'this salon has no plan').toBeNull();
  const owner = await identity.staff(legacy, 'OWNER');
  for (let n = 0; n < 7; n++) await team.add(legacy, owner.tokens);
  for (let n = 0; n < 3; n++) await team.createAccount(legacy, owner.tokens);
  expect((await team.list(legacy, owner.tokens)).length, 'all seven people').toBe(7);
  await team.dispose();
});

// ---------------------------------------------------------------- the last seat

/**
 * One round of the last-seat race: asserts exactly one winner, every loser told why and the seat count,
 * then frees the seat for the next round. `requests` fire in the order given, `gap` milliseconds apart.
 */
async function lastSeatRound(team: Team, owner: SignedStaff, requests: { kind: 'member' | 'account'; send: () => Promise<APIResponse> }[], gap: number, used: number, label: string) {
  const responses = await fire(requests.map(r => r.send), gap);
  const winners = responses.map((r, n) => ({ r, kind: requests[n].kind })).filter(x => worked(x.r));
  expect(winners.length, `${label}: exactly one request takes the last seat (answers ${responses.map(status).join(', ')})`).toBe(1);
  for (const loser of responses.filter(r => !worked(r))) await noSeat(loser, `${label}: a loser`);
  const { accounts, members } = await team.expectSeats(owner, used, `${label}: read back`);
  const winner = winners[0];
  const body = await winner.r.json() as { id: string; version: number };
  if (winner.kind === 'member') {
    expect(members.some(m => m.id === body.id), `${label}: the person who won is on the team`).toBe(true);
    await team.remove(owner.salon, owner.tokens, body);
  } else {
    expect(accounts.some(a => a.id === body.id), `${label}: the account that won exists`).toBe(true);
    await team.removeAccount(owner.salon, owner.tokens, body.id);
  }
  return winner.kind;
}

test('TC-F06-S01-031 AC-6: of two requests for the last seat, adding a person and creating an account, in both orders and at every small gap, exactly one succeeds every time and each side wins', async ({ identity, operator }, testInfo) => {
  const team = new Team(identity);
  const { salon, owner } = await teamSalon(operator, identity, 3);
  await team.add(salon, owner.tokens);
  await team.expectSeats(owner, 2, 'one seat left');
  const member = { kind: 'member' as const, send: () => team.addRaw(salon, owner.tokens, memberInput()) };
  const account = { kind: 'account' as const, send: () => team.createAccountRaw(salon, owner.tokens) };
  const tally = { member: 0, account: 0 };
  for (const gap of GAPS) {
    tally[await lastSeatRound(team, owner, [member, account], gap, 3, `person first by ${gap} ms`)]++;
    await team.expectSeats(owner, 2, 'the seat is free again');
    tally[await lastSeatRound(team, owner, [account, member], gap, 3, `account first by ${gap} ms`)]++;
    await team.expectSeats(owner, 2, 'the seat is free again');
  }
  await testInfo.attach('winners', { body: JSON.stringify(tally), contentType: 'application/json' });
  expect(tally.member, `a person won the seat at some gap, so that order was fought (${JSON.stringify(tally)})`).toBeGreaterThan(0);
  expect(tally.account, `an account won the seat at some gap, so that order was fought (${JSON.stringify(tally)})`).toBeGreaterThan(0);
  await team.dispose();
});

test('TC-F06-S01-032 AC-6: of sixteen requests for the last seat sent at once, half adding a person and half creating an account, exactly one succeeds and every other is told there is no seat', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner, created } = await teamSalon(operator, identity, 4);
  await team.add(salon, owner.tokens);
  await team.createAccount(salon, owner.tokens);
  await team.expectSeats(owner, 3, 'one seat left');
  const beforeMembers = (await team.list(salon, owner.tokens)).length;
  const beforeAccounts = (await identity.list(owner)).length;
  const responses = await Promise.all(Array.from({ length: 16 }, (_, n) => (n % 2
    ? team.addRaw(salon, owner.tokens, memberInput())
    : team.createAccountRaw(salon, owner.tokens))));
  expect(responses.filter(worked).length, `exactly one wins (answers ${responses.map(status).join(', ')})`).toBe(1);
  for (const loser of responses.filter(r => !worked(r))) await noSeat(loser, 'a loser');
  const { accounts, members } = await team.expectSeats(owner, 4, 'the limit, read back row by row');
  expect((members.length - beforeMembers) + (accounts.length - beforeAccounts), 'exactly one record was created').toBe(1);
  expect(created.id).toBeTruthy();
  await team.dispose();
});

test('TC-F06-S01-033 AC-6: unlinking also fights for the last seat: of an unlink, a new person and a new account, each led by the others in turn, exactly one succeeds and an unlink can win; a person added linked to a free account succeeds beside them because it needs no seat', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner } = await teamSalon(operator, identity, 3);
  const account = await team.createAccount(salon, owner.tokens);
  await team.expectSeats(owner, 2, 'one seat left');
  let unlinksWon = 0;
  for (const gap of GAPS) {
    await test.step(`the unlink first by ${gap} ms`, async () => {
      let person = await team.add(salon, owner.tokens, memberInput({ signInAccountId: account.id }));
      await team.expectSeats(owner, 2, 'the pair counts once');
      const [unlink, added, createdAccount, linkedOwner] = await fire([
        () => team.saveRaw(salon, owner.tokens, person.id, updateOf(person, {}, null)),
        () => team.addRaw(salon, owner.tokens, memberInput()),
        () => team.createAccountRaw(salon, owner.tokens),
        () => team.addRaw(salon, owner.tokens, memberInput({ signInAccountId: owner.principal.id })),
      ], gap);
      const seatTakers = [unlink, added, createdAccount];
      if (worked(unlink)) unlinksWon++;
      expect(seatTakers.filter(worked).length, `exactly one takes the seat (answers ${seatTakers.map(status).join(', ')})`).toBe(1);
      for (const loser of seatTakers.filter(r => !worked(r))) await noSeat(loser, 'a loser');
      expect(status(linkedOwner), 'adding someone linked to a free account needs no seat').toBe(201);
      await team.expectSeats(owner, 3, 'the limit, read back');
      // Back to the arrangement: free the seat and the Owner's account.
      if (worked(added)) await team.remove(salon, owner.tokens, await added.json() as TeamMember);
      if (worked(createdAccount)) await team.removeAccount(salon, owner.tokens, (await createdAccount.json() as { id: string }).id);
      await team.remove(salon, owner.tokens, await linkedOwner.json() as TeamMember);
      person = await team.get(salon, owner.tokens, person.id);
      await team.remove(salon, owner.tokens, person);
      await team.expectSeats(owner, 2, 'one seat left again');
    });
  }
  expect(unlinksWon, 'an unlink won the seat at some gap, so that order was fought').toBeGreaterThan(0);
  await team.dispose();
});

test('TC-F06-S01-034 AC-6: the operator\'s move to a plan of exactly the seats used, raced by a new person, by a new account and by both, at every small gap, never leaves the salon using more seats than its plan allows', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { admin, salon, owner, created, plan } = await teamSalon(operator, identity, 6);
  const small = await operator.plan(admin, { staffSeats: 2 });
  await team.add(salon, owner.tokens);
  await team.expectSeats(owner, 2, 'exactly what the small plan allows');
  const rows: [string, boolean, boolean][] = [['a new person', true, false], ['a new account', false, true], ['both', true, true]];
  for (const [label, withPerson, withAccount] of rows) {
    for (const gap of GAPS) {
      await test.step(`${label}, ${gap} ms after the move`, async () => {
        const version = await operator.version(admin, created.id);
        const seatRequests = [
          ...(withPerson ? [{ kind: 'person', send: () => team.addRaw(salon, owner.tokens, memberInput()) }] : []),
          ...(withAccount ? [{ kind: 'account', send: () => team.createAccountRaw(salon, owner.tokens) }] : []),
        ];
        const [move, ...answers] = await fire([() => operator.movePlan(admin, created.id, version, small.id), ...seatRequests.map(r => r.send)], gap);
        const said = `move ${status(move)}, ${seatRequests.map((r, n) => `${r.kind} ${status(answers[n])}`).join(', ')}`;
        const entitlements = await team.entitlements(salon, owner.tokens);
        expect(entitlements.usage.staffSeats, `${label}, ${gap} ms: seats used fit the plan the salon ended on (${said})`)
          .toBeLessThanOrEqual(entitlements.limits!.staffSeats!);
        if (!worked(move)) await operatorProblem(move, 409, 'entitlement_exceeded');
        for (const r of answers) if (!worked(r)) await noSeat(r, 'a refused seat');
        // Back to the arrangement: the large plan, and one person with no sign-in.
        for (const [n, r] of answers.entries()) {
          if (!worked(r)) continue;
          if (seatRequests[n].kind === 'person') await team.remove(salon, owner.tokens, await r.json() as TeamMember);
          else await team.removeAccount(salon, owner.tokens, (await r.json() as { id: string }).id);
        }
        if (worked(move)) await operatorJson(await operator.movePlan(admin, created.id, await operator.version(admin, created.id), plan.id), 'salon');
        await team.expectSeats(owner, 2, 'back to the arrangement');
      });
    }
  }
  await team.dispose();
});

test('TC-F06-S01-035 AC-5, AC-6: a storm of adds, new accounts, links, unlinks, edits and removals at once causes no deadlock and no server error, every request is answered, and the seats, the links and the limit are right afterwards', async ({ identity, operator }) => {
  test.setTimeout(240_000);
  const team = new Team(identity);
  const { salon, owner } = await teamSalon(operator, identity, 7);
  const deadlocksBefore = deadlocks();
  for (let round = 1; round <= 3; round++) {
    await test.step(`round ${round}`, async () => {
      const accounts = [await team.createAccount(salon, owner.tokens), await team.createAccount(salon, owner.tokens), await team.createAccount(salon, owner.tokens)];
      const linked = [await team.add(salon, owner.tokens, memberInput({ signInAccountId: accounts[0].id })), await team.add(salon, owner.tokens, memberInput({ signInAccountId: accounts[1].id }))];
      const unlinked = [await team.add(salon, owner.tokens), await team.add(salon, owner.tokens)];
      await team.expectSeats(owner, 6, 'one seat left');
      const responses = await Promise.all([
        ...Array.from({ length: 5 }, () => team.addRaw(salon, owner.tokens, memberInput())),
        ...Array.from({ length: 5 }, () => team.createAccountRaw(salon, owner.tokens)),
        team.saveRaw(salon, owner.tokens, linked[0].id, updateOf(linked[0], {}, null)), // an unlink needs a seat
        team.saveRaw(salon, owner.tokens, unlinked[0].id, updateOf(unlinked[0], {}, accounts[2].id)), // a link frees one
        team.saveRaw(salon, owner.tokens, unlinked[1].id, updateOf(unlinked[1], {}, accounts[2].id)), // the same account
        team.saveRaw(salon, owner.tokens, linked[1].id, updateOf(linked[1], { title: 'Edited in the storm' })),
        team.removeAccountRaw(salon, owner.tokens, accounts[1].id), // clears linked[1]'s link while it is edited
        team.removeRaw(salon, owner.tokens, unlinked[1].id, unlinked[1].version),
        ...Array.from({ length: 3 }, () => team.addRaw(salon, owner.tokens, memberInput({ signInAccountId: owner.principal.id }))), // one account, many people
      ]);
      const unexpected = responses.filter(r => !worked(r) && ![400, 404, 409].includes(status(r)));
      expect(unexpected.map(status), `no server error or lost request (answers ${responses.map(status).join(', ')})`).toEqual([]);
      for (const r of responses.filter(x => status(x) === 409)) {
        expect(['entitlement_exceeded', 'conflict'], '409 says why').toContain((await r.json() as { code: string }).code);
      }
      const active = await activeAccountIds(identity, owner);
      const members = await team.list(salon, owner.tokens);
      const links = members.map(m => m.signInAccountId).filter(Boolean) as string[];
      expect(links.filter(id => !active.has(id)), 'no person is linked to a removed account').toEqual([]);
      expect(new Set(links).size, 'no account has two people').toBe(links.length);
      const entitlements = await team.entitlements(salon, owner.tokens);
      await team.expectSeats(owner, active.size + members.filter(m => !m.signInAccountId).length, 'the count equals the rows');
      expect(entitlements.usage.staffSeats, 'and fits the plan').toBeLessThanOrEqual(7);
      // Back to the Owner alone, for the next round.
      for (const m of members) await team.remove(salon, owner.tokens, m);
      for (const id of [...active].filter(x => x !== owner.principal.id)) await team.removeAccount(salon, owner.tokens, id);
      await team.expectSeats(owner, 1, 'the Owner alone');
    });
  }
  // The database publishes the counter just after a transaction ends; its flush takes up to a second.
  await new Promise(resolve => setTimeout(resolve, 1500));
  expect(deadlocks() - deadlocksBefore, 'the database resolved no deadlock during the storm').toBe(0);
  expect((await identity.api.health()).status(), 'the API is healthy after it').toBe(200);
  await team.dispose();
});

// ---------------------------------------------------------------- the link

test('TC-F06-S01-036 AC-5: two saves linking one account at once leave it linked once, however they arrive: two edits, a burst of new people, and an edit with a new person; every loser is a 400 on signInAccountId', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner } = await teamSalon(operator, identity, 30);
  const rows: { label: string; send: (accountId: string) => Promise<Promise<APIResponse>[]> }[] = [
    { label: 'two edits', send: async id => {
      const [a, b] = [await team.add(salon, owner.tokens), await team.add(salon, owner.tokens)];
      return [team.saveRaw(salon, owner.tokens, a.id, updateOf(a, {}, id)), team.saveRaw(salon, owner.tokens, b.id, updateOf(b, {}, id))];
    } },
    { label: 'eight new people', send: async id => Array.from({ length: 8 }, () => team.addRaw(salon, owner.tokens, memberInput({ signInAccountId: id }))) },
    { label: 'an edit with a new person', send: async id => {
      const a = await team.add(salon, owner.tokens);
      return [team.saveRaw(salon, owner.tokens, a.id, updateOf(a, {}, id)), team.addRaw(salon, owner.tokens, memberInput({ signInAccountId: id }))];
    } },
  ];
  for (const row of rows) {
    await test.step(row.label, async () => {
      const account = await team.createAccount(salon, owner.tokens);
      const responses = await Promise.all(await row.send(account.id));
      expect(responses.filter(worked).length, `one link (answers ${responses.map(status).join(', ')})`).toBe(1);
      for (const loser of responses.filter(r => !worked(r))) await refusedOn(loser, 'signInAccountId', `${row.label}: a loser`);
      const linked = (await team.list(salon, owner.tokens)).filter(m => m.signInAccountId === account.id);
      expect(linked.length, 'read back: one person has the account').toBe(1);
      const listed = (await team.accounts(salon, owner.tokens)).find(a => a.id === account.id);
      expect(listed?.memberId, 'and the account names that person').toBe(linked[0].id);
    });
  }
  await team.dispose();
});

test('TC-F06-S01-037 AC-5, AC-6: an account removed while a person is being linked to it never leaves a link to a removed account, whichever arrives first, and the seats are right', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner } = await teamSalon(operator, identity, 6);
  const tally = { linked: 0, refused: 0 };
  const rounds = GAPS.flatMap(gap => [{ gap, linkFirst: true }, { gap, linkFirst: false }]);
  for (const { gap, linkFirst } of rounds) {
    await test.step(`${linkFirst ? 'the link' : 'the removal'} first by ${gap} ms`, async () => {
      const account = await team.createAccount(salon, owner.tokens);
      const person = await team.add(salon, owner.tokens);
      await team.expectSeats(owner, 3, 'the Owner, an account nobody is linked to, and the person');
      const link = () => team.saveRaw(salon, owner.tokens, person.id, updateOf(person, {}, account.id));
      const remove = () => team.removeAccountRaw(salon, owner.tokens, account.id);
      const answers = await fire(linkFirst ? [link, remove] : [remove, link], gap);
      const [linkAnswer, removeAnswer] = linkFirst ? answers : [answers[1], answers[0]];
      expect(status(removeAnswer), 'the account is removed').toBe(204);
      if (worked(linkAnswer)) tally.linked++;
      else { tally.refused++; await refusedOn(linkAnswer, 'signInAccountId', 'a link that lost'); }
      const after = await team.get(salon, owner.tokens, person.id);
      expect(after.signInAccountId ?? null, `the person has no sign-in (link ${status(linkAnswer)})`).toBeNull();
      await team.expectSeats(owner, 2, 'the Owner and the person with no sign-in');
      await team.remove(salon, owner.tokens, after);
    });
  }
  expect(tally.linked, `a link was saved before the removal at some gap, so that order was fought (${JSON.stringify(tally)})`).toBeGreaterThan(0);
  expect(tally.refused, `a removal beat the link at some gap, so that order was fought (${JSON.stringify(tally)})`).toBeGreaterThan(0);
  await team.dispose();
});

// ---------------------------------------------------------------- saves of one version

test('TC-F06-S01-038 AC-2: of eight saves of one version sent at once exactly one succeeds, the others are 409 conflict, and the member holds the winner\'s values', async ({ identity, operator }) => {
  const team = new Team(identity);
  const { salon, owner } = await teamSalon(operator, identity);
  const member = await team.add(salon, owner.tokens);
  const titles = Array.from({ length: 8 }, (_, n) => `Title ${n}`);
  const responses = await Promise.all(titles.map(title => team.saveRaw(salon, owner.tokens, member.id, updateOf(member, { title }))));
  const winners = responses.filter(worked);
  expect(winners.length, `exactly one save (answers ${responses.map(status).join(', ')})`).toBe(1);
  for (const loser of responses.filter(r => !worked(r))) await refused(loser, 409, 'conflict', 'a losing save');
  const won = await winners[0].json() as TeamMember;
  const after = await team.get(salon, owner.tokens, member.id);
  expect(after, 'read back: the winner\'s values').toEqual(won);
  expect(titles, 'one of the titles sent').toContain(after.title);
  expect(after.version, 'one new version').not.toBe(member.version);
  await team.dispose();
});
