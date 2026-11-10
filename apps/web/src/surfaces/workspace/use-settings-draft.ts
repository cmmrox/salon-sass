'use client';
import { useRouter } from 'next/navigation';
import { useState } from 'react';
import { useBrandingChanges, useDraft, useSetBrandingAnswer } from '@/features/branding';
import { IdentityFailure, leaveEndedSession, usePrincipal, type Kind } from '@/features/identity';
import { useReadFailure } from '@/lib/api-core/data-layer';

/** What a settings tab tells the person beside its form: a conflict, an outage, a refusal or fields to fix. */
export type SettingsNotice = 'conflict' | 'unavailable' | 'fix' | 'refused' | null;
type Busy = 'saving' | 'publishing' | 'discarding' | 'resetting' | null;
type Event = 'saved' | 'published' | 'discarded' | 'reset';

/** A draft the API answers: its version and whether customers see something else. */
type Draft = { version: number; hasUnpublishedChanges: boolean };

/** How one settings tab maps its draft to its form and back (F04-S01 Profile, F04-S02 Branding). */
export type SettingsDraftShape<D extends Draft, F, Field extends string> = {
  /** The BFF actions: `<subject>-draft`, `-save`, `-publish`, `-discard` (and `-reset` where there is one). */
  subject: 'profile' | 'brand';
  formOf: (draft: D) => F;
  sameForm: (a: F, b: F) => boolean;
  /** The save the form makes, or the field the browser can already tell is wrong. */
  saveOf: (form: F, draft: D) => { save: unknown } | { invalid: Field };
  fieldOf: (apiField: string) => Field | null;
};

/**
 * A settings tab's state, on F04-S01's pattern: the draft the API answered, the form typed over it, and
 * saving, publishing, discarding (and resetting) through the workspace BFF, each under the session's
 * cross-tab lock and naming the draft's version. A stale version is a conflict the person resolves by
 * reloading; a refused field shows its message beside it.
 */
export function useSettingsDraft<D extends Draft, F, Field extends string>(
  kind: Kind,
  shape: SettingsDraftShape<D, F, Field>,
  notify: (event: Event) => void,
) {
  const router = useRouter();
  const reads = { principal: usePrincipal(kind), draft: useDraft(kind, shape.subject) };
  const changes = useBrandingChanges(kind);
  const setAnswer = useSetBrandingAnswer(kind);
  const ready = Boolean(reads.principal.data && reads.draft.data);
  const draft = ready ? ((reads.draft.data ?? null) as D | null) : null;
  const [form, setForm] = useState<F | null>(null);
  // The form starts from each version of the draft read or saved, and keeps the person's edits in between.
  const [formOfDraft, setFormOfDraft] = useState<D | null>(null);
  const [errors, setErrors] = useState<Partial<Record<Field, boolean>>>({});
  const [notice, setNotice] = useState<SettingsNotice>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [busy, setBusy] = useState<Busy>(null);
  const loading =
    Object.values(reads).some((each) => each.isFetching) ||
    (!ready && !Object.values(reads).some((each) => each.error));

  function show(next: D) {
    setAnswer(`${shape.subject}-draft`, next as never);
    setFormOfDraft(next);
    setForm(shape.formOf(next));
    setErrors({});
    setNotice(null);
  }
  if (draft && draft !== formOfDraft) {
    setFormOfDraft(draft);
    setForm(shape.formOf(draft));
  }

  function failed(error: unknown) {
    const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
    if (failure.code === 'invalid_session') {
      leaveEndedSession(kind, router);
      return;
    }
    if (failure.code === 'conflict') return setNotice('conflict');
    if (failure.code === 'bad_request') {
      const fields = failure.fields.map(shape.fieldOf).filter((field): field is Field => field !== null);
      setErrors(Object.fromEntries(fields.map((field) => [field, true])) as Partial<Record<Field, boolean>>);
      return setNotice('fix');
    }
    if (failure.code === 'service_unavailable' || failure.code === 'browser_unsupported')
      return setNotice('unavailable');
    setRefusal(failure.code);
    setNotice('refused');
  }

  /** Reads the tab again after a conflict: what is on the server replaces what was edited. */
  async function load() {
    const [principal, next] = await Promise.all([reads.principal.refetch(), reads.draft.refetch()]);
    if (principal.error || next.error || !next.data) return;
    show(next.data as unknown as D);
  }

  useReadFailure(Object.values(reads), (failure) => failed(failure));

  /** One change of the draft, made once; the tab shows what it answers. */
  const request = changes.run;

  async function saveDraft(current: D, values: F): Promise<D | null> {
    const save = shape.saveOf(values, current);
    if ('invalid' in save) {
      setErrors({ [save.invalid]: true } as Partial<Record<Field, boolean>>);
      setNotice('fix');
      return null;
    }
    try {
      const saved = (await request(`${shape.subject}-save`, save.save as Record<string, unknown>)) as unknown as D;
      show(saved);
      return saved;
    } catch (error) {
      failed(error);
      return null;
    }
  }

  async function change(
    state: Busy,
    action: `${'profile' | 'brand'}-${'publish' | 'discard'}` | 'brand-reset',
    event: Event,
    version: number,
  ) {
    setBusy(state);
    try {
      show((await request(action, { version })) as unknown as D);
      notify(event);
    } catch (error) {
      failed(error);
    }
    setBusy(null);
  }

  const dirty = Boolean(draft && form && !shape.sameForm(form, shape.formOf(draft)));

  return {
    draft,
    form,
    errors,
    notice,
    refusal,
    loading,
    busy,
    dirty,
    unpublished: Boolean(draft?.hasUnpublishedChanges) || dirty,
    setForm,
    request,
    failed,
    reload: load,
    save: async () => {
      if (!draft || !form) return;
      setBusy('saving');
      if (await saveDraft(draft, form)) notify('saved');
      setBusy(null);
    },
    publish: async () => {
      if (!draft || !form) return;
      setBusy('publishing');
      const current = dirty ? await saveDraft(draft, form) : draft;
      setBusy(null);
      if (current) await change('publishing', `${shape.subject}-publish`, 'published', current.version);
    },
    discard: async () => {
      if (draft) await change('discarding', `${shape.subject}-discard`, 'discarded', draft.version);
    },
    reset: async () => {
      // Only the brand has a reset (F04-S02).
      if (draft && shape.subject === 'brand') await change('resetting', 'brand-reset', 'reset', draft.version);
    },
  };
}
