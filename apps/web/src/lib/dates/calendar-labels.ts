/**
 * The accessible names of a Calendar's day buttons (F04-S03): the date written out with its plain day number
 * ("Monday, 2 November 2026"), so the number the button shows is a word of its name (WCAG 2.5.3, Label in
 * Name; react-day-picker's own labels say "2nd"). Today and the chosen day are named as such.
 */
export function dayButtonLabel(date: Date, modifiers: { today?: boolean; selected?: boolean }) {
  const written = new Intl.DateTimeFormat('en-LK', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(date);
  return [modifiers.today ? 'Today' : null, written, modifiers.selected ? 'selected' : null].filter(Boolean).join(', ');
}
