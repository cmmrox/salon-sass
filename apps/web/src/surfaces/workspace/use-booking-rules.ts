'use client';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import {
  choose as chooseRule,
  errorOfField,
  fieldOfError,
  formOf,
  sameRules,
  saveOf,
  shownErrors,
  typeNumber,
  useBookingChanges,
  useBookingRulesHistory,
  useBookingRulesRead,
  useSetBookingAnswer,
  type BookingRulesSettings,
  type HistoryState,
  type NumberField,
  type RulesForm,
  type RulesValues,
} from '@/features/booking';
import { IdentityFailure, leaveEndedSession, usePrincipal, type Kind } from '@/features/identity';
import { useReadFailure } from '@/lib/api-core/data-layer';
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
  const reads = { principal: usePrincipal(kind), settings: useBookingRulesRead(kind) };
  const change = useBookingChanges(kind).run;
  const setAnswer = useSetBookingAnswer(kind);
  const ready = Boolean(reads.principal.data && reads.settings.data);
  const settings = ready ? (reads.settings.data ?? null) : null;
  const viewer = reads.principal.data?.staffId ?? null;
  // The form starts from each version of the rules read or saved, and keeps the person's edits in between.
  const [form, setForm] = useState<RulesForm | null>(null);
  const [formOfSettings, setFormOfSettings] = useState<BookingRulesSettings | null>(null);
  if (settings && settings !== formOfSettings) {
    setFormOfSettings(settings);
    setForm(formOf(settings.rules));
  }
  const loading =
    Object.values(reads).some((each) => each.isFetching) ||
    (!ready && !Object.values(reads).some((each) => each.error));
  const [notice, setNotice] = useState<RulesNotice>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [historyPage, setHistoryPage] = useState(0);
  const historyRead = useBookingRulesHistory(kind, historyPage, historyOpen);
  const history: HistoryState =
    historyRead.isFetching || (!historyRead.data && !historyRead.error)
      ? { state: 'loading' }
      : historyRead.error || !historyRead.data
        ? { state: 'unavailable' }
        : { state: 'ready', history: historyRead.data };

  function show(next: BookingRulesSettings) {
    setAnswer('booking-rules', next);
    setFormOfSettings(next);
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

  /** Reads the tab again after a conflict: what is on the server replaces what was edited. */
  async function load() {
    const [principal, next] = await Promise.all([reads.principal.refetch(), reads.settings.refetch()]);
    if (principal.error || next.error || !next.data) return;
    setFormOfSettings(next.data);
    setForm(formOf(next.data.rules));
    setNotice(null);
  }

  useReadFailure(Object.values(reads), (failure) => failed(failure));
  useReadFailure([historyRead], (failure) => {
    if (failure.code === 'invalid_session') leaveEndedSession(kind, router);
  });

  async function save() {
    if (!settings || !form || Object.keys(shownErrors(form)).length > 0) return;
    setSaving(true);
    try {
      const saved = await change('booking-rules-save', { version: settings.version, rules: saveOf(form.values) });
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
      // Opening it reads its first page again.
      setHistoryOpen(open);
      if (open) setHistoryPage(0);
    },
    historyPage: (page: number) => setHistoryPage(page),
  };
}
