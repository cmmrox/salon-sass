import { expect } from 'vitest';

/**
 * A list announces its items and their count (BUG-F03-003): an element with the list role holds
 * only list items, `count` of them, as axe's aria-required-children rule requires.
 */
export function expectListOfItems(list: HTMLElement, count: number) {
  expect(list).toHaveAttribute('role', 'list');
  const children = Array.from(list.children);
  expect(children.map((child) => child.getAttribute('role') ?? child.tagName.toLowerCase())).toEqual(Array(count).fill('listitem'));
}
