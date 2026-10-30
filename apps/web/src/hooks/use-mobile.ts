import * as React from 'react';

// The workspace's `rail` breakpoint (app/globals.css, `--breakpoint-rail`): below it the Sidebar is
// its phone sheet, from it a fixed rail (CR-017). hooks/use-mobile.test.ts keeps the two equal.
export const MOBILE_BREAKPOINT = 900;

const QUERY = `(max-width: ${MOBILE_BREAKPOINT - 1}px)`;

function subscribe(onChange: () => void) {
  const mql = window.matchMedia(QUERY);
  mql.addEventListener('change', onChange);
  return () => mql.removeEventListener('change', onChange);
}

// Read as the CLI's hook does, from the viewport width, through useSyncExternalStore rather than
// state set in an effect (the project's react-hooks lint). The server, which has no viewport,
// renders the rail's markup, which is hidden below 900px by its `rail:` classes until hydration.
export function useIsMobile() {
  return React.useSyncExternalStore(
    subscribe,
    () => window.innerWidth < MOBILE_BREAKPOINT,
    () => false,
  );
}
