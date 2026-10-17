import { fireEvent, screen } from '@testing-library/react';

/**
 * Chooses an option of a shadcn/ui Select as a keyboard user does (docs/engineering/web.md,
 * "Styling", rule 11): Enter on the trigger opens the list, Enter on the option chooses it.
 */
export async function chooseOption(trigger: HTMLElement, name: string) {
  fireEvent.keyDown(trigger, { key: 'Enter' });
  const option = await screen.findByRole('option', { name });
  fireEvent.keyDown(option, { key: 'Enter' });
}
