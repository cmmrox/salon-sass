'use client';
import { useEffect, useState } from 'react';

/**
 * Asks before the person leaves a page with unsaved edits (F04-S01 AC-11): the browser's own prompt when
 * the tab closes or reloads, and, for a link inside the workspace (the navigation, the header, a tab),
 * the page's own confirmation. While `guarded`, a click on such a link is held and its address returned
 * as `pending`; `leave` follows it, `stay` forgets it. `hold` holds an address the page itself was about to
 * go to (a Settings tab chosen with the keyboard or the pointer, which is not a link; F04-S04).
 */
export function useLeaveGuard(guarded: boolean, follow: (href: string) => void) {
  const [pending, setPending] = useState<string | null>(null);
  useEffect(() => {
    if (!guarded) return;
    const beforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
    };
    const click = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey) return;
      const link = (event.target as Element | null)?.closest?.('a[href]');
      if (!(link instanceof HTMLAnchorElement) || link.target === '_blank') return;
      const url = new URL(link.href, window.location.href);
      if (url.origin !== window.location.origin || url.pathname === window.location.pathname) return;
      event.preventDefault();
      event.stopPropagation();
      setPending(url.pathname + url.search);
    };
    window.addEventListener('beforeunload', beforeUnload);
    document.addEventListener('click', click, true);
    return () => {
      window.removeEventListener('beforeunload', beforeUnload);
      document.removeEventListener('click', click, true);
    };
  }, [guarded]);
  return {
    pending,
    stay: () => setPending(null),
    hold: (href: string) => setPending(href),
    leave: () => {
      const href = pending;
      setPending(null);
      if (href) follow(href);
    },
  };
}
