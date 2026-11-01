'use client';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import {
  choose as chooseRule,
  errorOfField,
  fieldOfError,
  formOf,
  sameRules,
  saveOf,
  shownErrors,
  typeNumber,
  type BookingRulesHistory,
  type BookingRulesSettings,
  type HistoryState,
  type NumberField,
  type RulesForm,
  type RulesValues,
} from '@/features/booking';
import {
  ensurePrincipal,
  IdentityFailure,
  identityRequest,
  leaveEndedSession,
  principalInsideLock,
  supportsLocks,
  withIdentityLock,
  type Kind,
} from '@/features/identity';
import type { SettingsNotice } from './use-settings-draft';

/** What the tab says above the form: the shared settings notices, or that a save changed nothing. */
export type RulesNotice = SettingsNotice | 'unchanged';

/**
 * The Booking rules tab's state (F04-S04): the rules as settings staff keep them, the form with its typed
 * fields and their errors, the save (a new version at once, made from the version read), and the history,
 * read when its sheet opens. The save runs under the session's cross-tab lock.
 */
export function useBookingRules(kind: Kind, notify: () => void) {
  const router = useRouter();
  const [settings, setSettings] = useState<BookingRulesSettings | null>(null);
  const [form, setForm] = useState<RulesForm | null>(null);
  const [viewer, setViewer] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<RulesNotice>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [history, setHistory] = useState<HistoryState>({ state: 'loading' });

  function show(next: BookingRulesSettings) {
    setSettings(next);
    setForm(formOf(next.rules));
  }

  function failed(error: unknown): IdentityFailure | null {
    const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
    if (failure.code === 'invalid_session') {
      leaveEndedSession(kind, router);
      return null;
    }
    if (failure.code === 'conflict') {
      setNotice('conflict');
      return null;
    }
    if (failure.code === 'invalid_state') {
      setNotice('unchanged');
      return null;
    }
    if (failure.code === 'service_unavailable' || failure.code === 'browser_unsupported') {
      setNotice('unavailable');
      return null;
    }
    if (failure.code === 'bad_request') return failure;
    setRefusal(failure.code);
    setNotice('refused');
    return null;
  }

  async function load() {
    setLoading(true);
    try {
      const principal = await ensurePrincipal(kind);
      setViewer(principal.staffId ?? null);
      show(await identityRequest<BookingRulesSettings>(kind, 'booking-rules'));
      setNotice(null);
    } catch (error) {
      failed(error);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    let active = true;
    Promise.resolve().then(() => {
      if (active) void load();
    });
    return () => {
      active = false;
    };
    // Loaded once when the tab opens; reload is the person's choice after a conflict.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function readHistory(page: number) {
    setHistory({ state: 'loading' });
    try {
      setHistory({
        state: 'ready',
        history: await identityRequest<BookingRulesHistory>(kind, `booking-rules-history?page=${page}`),
      });
    } catch (error) {
      const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
      if (failure.code === 'invalid_session') leaveEndedSession(kind, router);
      else setHistory({ state: 'unavailable' });
    }
  }

  async function save() {
    if (!settings || !form || Object.keys(shownErrors(form)).length > 0) return;
    if (!supportsLocks()) {
      setNotice('unavailable');
      return;
    }
    setSaving(true);
    try {
      const saved = await withIdentityLock(kind, async () => {
        await principalInsideLock(kind);
        return identityRequest<BookingRulesSettings>(kind, 'booking-rules-save', {
          version: settings.version,
          rules: saveOf(form.values),
        });
      });
      show(saved);
      setNotice(null);
      notify();
    } catch (error) {
      const refused = failed(error);
      if (refused) {
        const fields = refused.fields.map(fieldOfError).filter((field): field is NumberField => field !== null);
        setForm((current) =>
          current
            ? {
                ...current,
                errors: { ...current.errors, ...Object.fromEntries(fields.map((f) => [f, errorOfField(f)])) },
              }
            : current,
        );
        setNotice('fix');
      }
    }
    setSaving(false);
  }

  const errors = form ? shownErrors(form) : {};
  // Unsaved edits are what differs from what the form opened with: the current version, or the suggested rules.
  const dirty = Boolean(form && settings && !sameRules(form.values, formOf(settings.rules).values));

  return {
    settings,
    form,
    errors,
    viewer,
    loading,
    notice,
    refusal,
    saving,
    dirty,
    /** Before the first save the suggested rules can be saved as they are; afterwards only a change. */
    canSave: Boolean(form && settings) && Object.keys(errors).length === 0 && (settings?.rules === null || dirty),
    historyOpen,
    history,
    reload: load,
    save,
    choose: <K extends keyof RulesValues>(key: K, value: RulesValues[K]) =>
      setForm((current) => (current ? chooseRule(current, key, value) : current)),
    type: (field: NumberField, text: string) =>
      setForm((current) => (current ? typeNumber(current, field, text) : current)),
    setHistoryOpen: (open: boolean) => {
      setHistoryOpen(open);
      if (open) void readHistory(0);
    },
    historyPage: (page: number) => void readHistory(page),
  };
}
