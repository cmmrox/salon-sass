import { fireEvent, isInaccessible, waitFor, within } from '@testing-library/react';
import { expect } from 'vitest';

/**
 * Chooses an option of a shadcn/ui Select as a keyboard user does (docs/engineering/web.md,
 * "Styling", rule 11): Enter on the trigger opens the list, Enter on the option chooses it.
 *
 * The option is the one accessible option of the list this trigger opened (the one its `aria-controls` names)
 * whose accessible name is `name`. A role query works that out by computing the accessible name of every
 * option, which in jsdom means the style of every node, and a list of 96 times made it most of a test's time;
 * so the option whose own text is `name` is found first, and only it is checked for its role, its accessible
 * name and that it is not hidden. An option named otherwise than by its text is found by the role query.
 */
export async function chooseOption(trigger: HTMLElement, name: string) {
  fireEvent.keyDown(trigger, { key: 'Enter' });
  const list = await waitFor(() => {
    const id = trigger.getAttribute('aria-controls');
    const element = id ? document.getElementById(id) : null;
    if (!element) throw new Error(`The Select ${trigger.getAttribute('aria-label') ?? ''} opened no list`);
    return element;
  });
  const option = byOwnText(list, name) ?? (await within(list).findByRole('option', { name }));
  fireEvent.keyDown(option, { key: 'Enter' });
}

function byOwnText(list: HTMLElement, name: string): HTMLElement | null {
  const options = within(list)
    .queryAllByText(name)
    .map((text) => text.closest<HTMLElement>('[role="option"]'))
    .filter((option): option is HTMLElement => option !== null && list.contains(option));
  if (options.length !== 1) return null;
  const [option] = options as [HTMLElement];
  expect(option).toHaveAccessibleName(name);
  expect(isInaccessible(option), `the option ${name} is hidden`).toBe(false);
  return option;
}
