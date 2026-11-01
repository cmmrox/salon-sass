import { describe, expect, it } from 'vitest';
import type { Category } from '../types';
import { categoryProblem, categoryProblemOfField } from './category-form';

const colour: Category = { id: 'c', name: 'Colour', position: 2, serviceCount: 0, version: 0 };

describe('a category name (F05-S01 AC-2)', () => {
  it('is 2 to 30 characters after trimming', () => {
    expect(categoryProblem(' K ', [], null)).toBe('length');
    expect(categoryProblem('x'.repeat(31), [], null)).toBe('length');
    expect(categoryProblem('  Kids  ', [], null)).toBeNull();
    expect(categoryProblem('y'.repeat(30), [], null)).toBeNull();
  });

  it('is unique regardless of case, except for the category being renamed', () => {
    expect(categoryProblem('COLOUR', [colour], null)).toBe('taken');
    expect(categoryProblem('colour', [colour], 'c')).toBeNull();
  });

  it('reads the API’s field error as taken when the length is right', () => {
    expect(categoryProblemOfField('Colour')).toBe('taken');
    expect(categoryProblemOfField('C')).toBe('length');
  });
});
