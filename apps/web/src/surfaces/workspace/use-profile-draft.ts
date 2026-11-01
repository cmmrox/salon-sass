'use client';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import {
  fieldOf,
  formOf,
  sameForm,
  saveOf,
  type ProfileDraft,
  type ProfileField,
  type ProfileFormValues,
} from '@/features/branding';
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

/** What the Profile tab tells the person beside its form: a conflict, an outage, a refusal or fields to fix. */
export type ProfileNotice = 'conflict' | 'unavailable' | 'fix' | 'refused' | null;
type Busy = 'saving' | 'publishing' | 'discarding' | null;

/**
 * The Profile tab's state (F04-S01): the draft the API answered, the form being typed over it, and saving,
 * publishing and discarding through the workspace BFF, each under the session's cross-tab lock and naming
 * the draft's version. A stale version is a conflict the person resolves by reloading.
 */
export function useProfileDraft(kind: Kind, notify: (event: 'saved' | 'published' | 'discarded') => void) {
  const router = useRouter();
  const [draft, setDraft] = useState<ProfileDraft | null>(null);
  const [form, setForm] = useState<ProfileFormValues | null>(null);
  const [errors, setErrors] = useState<Partial<Record<ProfileField, boolean>>>({});
  const [notice, setNotice] = useState<ProfileNotice>(null);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<Busy>(null);

  function show(next: ProfileDraft) {
    setDraft(next);
    setForm(formOf(next));
    setErrors({});
    setNotice(null);
  }

  function failed(error: unknown) {
    const failure = error instanceof IdentityFailure ? error : new IdentityFailure('service_unavailable');
    if (failure.code === 'invalid_session') {
      leaveEndedSession(kind, router);
      return;
    }
    if (failure.code === 'conflict') return setNotice('conflict');
    if (failure.code === 'bad_request') {
      const fields = failure.fields.map(fieldOf).filter((field): field is ProfileField => field !== null);
      setErrors(Object.fromEntries(fields.map((field) => [field, true])));
      return setNotice('fix');
    }
    if (failure.code === 'service_unavailable' || failure.code === 'browser_unsupported')
      return setNotice('unavailable');
    setRefusal(failure.code);
    setNotice('refused');
  }

  async function load() {
    setLoading(true);
    try {
      await ensurePrincipal(kind);
      show(await identityRequest<ProfileDraft>(kind, 'profile-draft'));
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

  async function change(action: string, body: unknown): Promise<ProfileDraft> {
    if (!supportsLocks()) throw new IdentityFailure('browser_unsupported');
    return withIdentityLock(kind, async () => {
      await principalInsideLock(kind);
      return identityRequest<ProfileDraft>(kind, action, body);
    });
  }

  /** Saves the form as the draft; the draft as saved, or null when it was refused. */
  async function saveDraft(current: ProfileDraft, values: ProfileFormValues): Promise<ProfileDraft | null> {
    const save = saveOf(values, current);
    if ('invalid' in save) {
      setErrors({ [save.invalid]: true });
      setNotice('fix');
      return null;
    }
    try {
      const saved = await change('profile-save', save.save);
      show(saved);
      return saved;
    } catch (error) {
      failed(error);
      return null;
    }
  }

  const dirty = Boolean(draft && form && !sameForm(form, formOf(draft)));

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
    edit: (field: ProfileField, value: string) => setForm((current) => current && { ...current, [field]: value }),
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
      if (current) {
        try {
          show(await change('profile-publish', { version: current.version }));
          notify('published');
        } catch (error) {
          failed(error);
        }
      }
      setBusy(null);
    },
    discard: async () => {
      if (!draft) return;
      setBusy('discarding');
      try {
        show(await change('profile-discard', { version: draft.version }));
        notify('discarded');
      } catch (error) {
        failed(error);
      }
      setBusy(null);
    },
  };
}
