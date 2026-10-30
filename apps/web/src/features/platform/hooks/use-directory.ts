'use client';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { operatorRead, OperatorFailure } from '@/features/platform/client/operator-session';

export type DirectoryPage<T> = { items: T[]; page: number; size: number; total: number };

export const PAGE_SIZE = 20;

/**
 * A console directory's live state: the submitted search, the page in view, its rows, and whether it
 * is loading or failed. Pages in place; a lost session goes to sign-in.
 */
export function useDirectory<T>(action: 'salons' | 'plans') {
  const router = useRouter();
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<DirectoryPage<T> | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<OperatorFailure | null>(null);
  const load = useCallback(async (nextPage: number, nextQuery: string) => {
    setLoading(true); setFailure(null);
    try {
      const params = new URLSearchParams({ page: String(nextPage), size: String(PAGE_SIZE) });
      if (nextQuery.trim()) params.set('q', nextQuery.trim());
      const result = await operatorRead<DirectoryPage<T>>(`${action}?${params.toString()}`);
      setData(result); setPage(nextPage); setQuery(nextQuery);
    } catch (error) {
      const f = error instanceof OperatorFailure ? error : new OperatorFailure('service_unavailable');
      if (f.code === 'invalid_session') { router.replace('/console/sign-in'); router.refresh(); return; }
      setFailure(f);
    } finally { setLoading(false); }
  }, [action, router]);
  useEffect(() => { let active = true; Promise.resolve().then(() => { if (active) void load(0, ''); }); return () => { active = false; }; }, [load]);
  return { query, page, data, loading, failure, load };
}
