'use client';
import { ErrorNotice } from '@/components/shared/ErrorNotice';

/** A page of this route group that failed while rendering: said in plain words, with Try again (web M3). */
export default function RouteError({ retry }: { error: Error & { digest?: string }; retry: () => void }) {
  return <ErrorNotice retry={retry} />;
}
