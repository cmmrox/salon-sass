import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { chooseOption } from '@test/select';
import { TimeSelect } from './TimeSelect';

/** A time Select whose value its owner keeps, as every screen that uses one does. */
function Owned({ initial, onChange }: { initial: string; onChange: (value: string) => void }) {
  const [value, setValue] = useState(initial);
  return (
    <TimeSelect
      value={value}
      onChange={(next) => {
        setValue(next);
        onChange(next);
      }}
      label="Monday opens"
      testId="opens"
    />
  );
}
const show = (initial = '09:00') => {
  const onChange = vi.fn();
  render(<Owned initial={initial} onChange={onChange} />);
  return { trigger: screen.getByTestId('opens'), onChange };
};
const type = (element: Element, keys: string) => {
  for (const key of keys) fireEvent.keyDown(element, { key });
};

describe('a time Select', () => {
  it('shows the chosen time on the clock the product uses, with nothing offered while it is closed', () => {
    const { trigger } = show('09:30');
    expect(trigger).toHaveAccessibleName('Monday opens');
    expect(trigger).toHaveTextContent('09:30');
    expect(screen.queryByRole('option')).toBeNull();
  });

  it('offers every quarter hour of the day once opened, the chosen one selected', async () => {
    const { trigger } = show('09:30');
    fireEvent.keyDown(trigger, { key: 'Enter' });
    const list = await screen.findByRole('listbox');
    const options = within(list).getAllByRole('option');
    expect(options).toHaveLength(96);
    expect(options[0]).toHaveAccessibleName('00:00');
    expect(options[95]).toHaveAccessibleName('23:45');
    expect(within(list).getByRole('option', { name: '09:30' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('opens-option-0930')).toBe(within(list).getByRole('option', { name: '09:30' }));
  });

  it('takes the time chosen from the list, and shows it once closed', async () => {
    const { trigger, onChange } = show();
    await chooseOption(trigger, '17:45');
    expect(onChange).toHaveBeenCalledWith('17:45');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(trigger).toHaveTextContent('17:45');
  });

  it('jumps to a time typed on the closed control', () => {
    const { trigger, onChange } = show();
    act(() => trigger.focus());
    type(trigger, '14');
    expect(onChange).toHaveBeenLastCalledWith('14:00');
    expect(trigger).toHaveTextContent('14:00');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('moves to a time typed in the open list, and Enter chooses it', async () => {
    const { trigger, onChange } = show();
    fireEvent.keyDown(trigger, { key: 'Enter' });
    const list = await screen.findByRole('listbox');
    type(list, '16');
    const highlighted = within(list).getByRole('option', { name: '16:00' });
    // Radix moves the focus to the option it found on the next task.
    await waitFor(() => expect(highlighted).toHaveFocus());
    fireEvent.keyDown(highlighted, { key: 'Enter' });
    expect(onChange).toHaveBeenCalledWith('16:00');
    expect(trigger).toHaveTextContent('16:00');
  });
});
