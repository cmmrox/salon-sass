import type { Category } from '../types';

/** A category name's broken rule (F05-S01 AC-2, the prototype's `ceSave`). */
export type CategoryProblem = 'length' | 'taken';

const length = (text: string) => Array.from(text).length;

/**
 * What is wrong with a category name, if anything: 2 to 30 characters after trimming, and no other category with it
 * regardless of case. The API checks for real.
 */
export function categoryProblem(name: string, categories: Category[], renaming: string | null): CategoryProblem | null {
  const trimmed = name.trim();
  if (length(trimmed) < 2 || length(trimmed) > 30) return 'length';
  const taken = categories.some(
    (category) => category.id !== renaming && category.name.toLowerCase() === trimmed.toLowerCase(),
  );
  return taken ? 'taken' : null;
}

/** The API's field error on `name` means the same as the browser's own check, or that the name is taken. */
export function categoryProblemOfField(name: string): CategoryProblem {
  const trimmed = name.trim();
  return length(trimmed) >= 2 && length(trimmed) <= 30 ? 'taken' : 'length';
}
