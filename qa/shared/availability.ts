/**
 * F07-S02 expected free times, worked out here from the rules the spec states, never read from the product:
 * a start is on the slot interval counted from local midnight, the whole booking fits inside one of the person's
 * working windows (already inside the salon's opening hours, without breaks, leave and blocks), and overlaps no live
 * booking or unexpired hold of theirs, its buffer included; touching is allowed. Today only starts after now count.
 * "No preference" offers each time once, with the eligible person the strategy picks among those free then: fewest
 * (or most) minutes of live bookings and unexpired holds that day, ties in the Team page's order (AC-3, decision 108 a).
 *
 * Times are minutes after local midnight; `HH:mm` at the edges.
 */
export type Range = [number, number];

export const toMinutes = (hhmm: string) => { const [h, m] = hhmm.split(':').map(Number); return h * 60 + m; };
export const toClock = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
export const range = (from: string, to: string): Range => [toMinutes(from), toMinutes(to)];

/** Ranges cut by others: what remains of `windows` after removing `cuts`. */
export function minus(windows: Range[], cuts: Range[]): Range[] {
  let out = windows.map(w => [...w] as Range);
  for (const [cs, ce] of cuts) {
    out = out.flatMap(([ws, we]): Range[] => {
      if (ce <= ws || cs >= we) return [[ws, we]];
      return [...(cs > ws ? [[ws, cs] as Range] : []), ...(ce < we ? [[ce, we] as Range] : [])];
    });
  }
  return out;
}
export const intersect = (a: Range[], b: Range[]): Range[] =>
  a.flatMap(([as, ae]) => b.map(([bs, be]) => [Math.max(as, bs), Math.min(ae, be)] as Range)).filter(([s, e]) => e > s);

export type Person = {
  id: string; name: string;
  /** Their working windows that day (after breaks, leave and blocks), inside the salon's opening hours. */
  windows: Range[];
  /** What their live bookings and unexpired holds occupy that day, buffers included. */
  busy: Range[];
};

/** One person's starts: on the interval from midnight, fitting a window whole, overlapping nothing busy, after `after`. */
export function startsFor(p: Person, length: number, interval: number, after = -1): number[] {
  const out: number[] = [];
  for (let t = 0; t + length <= 24 * 60; t += interval) {
    if (t <= after) continue;
    const fits = p.windows.some(([s, e]) => t >= s && t + length <= e);
    const clashes = p.busy.some(([s, e]) => t < e && t + length > s);
    if (fits && !clashes) out.push(t);
  }
  return out;
}

export const bookedMinutes = (p: Person) => p.busy.reduce((n, [s, e]) => n + (e - s), 0);

/** The "No preference" answer: each start once, with the person the strategy picks; `people` in the Team page's order. */
export function noPreference(people: Person[], length: number, interval: number, strategy: 'least_booked' | 'most_booked', after = -1) {
  const starts = new Map<number, Person[]>();
  for (const p of people) for (const t of startsFor(p, length, interval, after)) starts.set(t, [...(starts.get(t) ?? []), p]);
  return [...starts.keys()].sort((a, b) => a - b).map(t => {
    const free = starts.get(t)!;
    // Stable sort keeps the Team page's order for ties.
    const chosen = [...free].sort((a, b) => strategy === 'least_booked' ? bookedMinutes(a) - bookedMinutes(b) : bookedMinutes(b) - bookedMinutes(a))[0];
    return { time: toClock(t), memberId: chosen.id };
  });
}
