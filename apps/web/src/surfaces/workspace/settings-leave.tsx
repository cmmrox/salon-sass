'use client';
import { createContext, useContext, useEffect, useMemo, useRef, type ReactNode } from 'react';

/** Takes over a move to another Settings tab: true when it held the move (to ask first), false to let it go. */
type Holder = (href: string) => boolean;

type SettingsLeave = {
  /** The tab on screen registers its holder, or null when it has nothing to ask about. */
  register: (holder: Holder | null) => void;
  /** Whether the registered holder held the move. */
  held: (href: string) => boolean;
};

const Context = createContext<SettingsLeave | null>(null);

/**
 * The Settings page's hold on its own tab changes (F04-S04 AC-10). A tab is a Tabs trigger, not a link, so the
 * unsaved-edits guard cannot see it as it sees links; the tab on screen registers a holder here instead, and
 * the Settings page asks it before going to another tab.
 */
export function SettingsLeaveProvider({ children }: { children: ReactNode }) {
  const holder = useRef<Holder | null>(null);
  const leave = useMemo<SettingsLeave>(
    () => ({
      register: (next) => {
        holder.current = next;
      },
      held: (href) => holder.current?.(href) ?? false,
    }),
    [],
  );
  return <Context.Provider value={leave}>{children}</Context.Provider>;
}

/** Whether the tab on screen held a move to `href`; the Settings page goes there itself when not. */
export function useSettingsLeaveCheck(): (href: string) => boolean {
  const leave = useContext(Context);
  return (href) => leave?.held(href) ?? false;
}

/** Registers the tab's holder while it is on screen: `hold` when it has unsaved edits, nothing otherwise. */
export function useSettingsLeaveHold(guarded: boolean, hold: (href: string) => void) {
  const leave = useContext(Context);
  const latest = useRef(hold);
  useEffect(() => {
    latest.current = hold;
  });
  useEffect(() => {
    if (!leave) return;
    leave.register(
      guarded
        ? (href) => {
            latest.current(href);
            return true;
          }
        : null,
    );
    return () => leave.register(null);
  }, [guarded, leave]);
}
