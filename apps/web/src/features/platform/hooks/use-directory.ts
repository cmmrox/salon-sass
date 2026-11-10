'use client';
import { useConsolePages, type DirectoryPage } from '../client/console-data';

export const PAGE_SIZE = 20;

/**
 * A console directory's live state: the submitted search, the page in view, its rows, and whether it
 * is loading or failed. Pages in place; a lost session goes to sign-in.
 */
export function useDirectory<T>(action: 'salons' | 'plans') {
  const directory = useConsolePages(action, (page, search) => ({
    page,
    size: PAGE_SIZE,
    ...(search.trim() ? { q: search.trim() } : {}),
  }));
  return {
    query: directory.search,
    page: directory.page,
    data: directory.data as DirectoryPage<T> | null,
    loading: directory.loading,
    failure: directory.failure,
    load: directory.load,
  };
}
