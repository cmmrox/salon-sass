'use client';
import { useEffect, useId, useRef, type ReactNode } from 'react';
import styles from './Confirmation.module.css';
/** Native modal supplies an inert background; explicit focus wrapping also keeps keyboard actions in the sheet. */
export function Confirmation({ title, children, onCancel, workspace = false, busy = false }: { title: string; children: ReactNode; workspace?: boolean; busy?: boolean; onCancel: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const heading = useId();
  const cancel = () => { if (!busy) onCancel(); };
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const node = dialog.current;
    node?.showModal();
    node?.querySelector<HTMLButtonElement>('[data-testid="account-cancel-action"]')?.focus();
    return () => { node?.close(); previous?.focus(); };
  }, []);
  useEffect(() => { if (busy) dialog.current?.focus(); }, [busy]);
  return <dialog ref={dialog} className={`${styles.dialog} ${workspace ? styles.workspace : ''}`} aria-labelledby={heading} aria-modal="true" tabIndex={-1} data-testid="account-confirmation" onKeyDown={(event) => { if (event.key !== 'Tab') return; const controls = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')); if (!controls.length) { event.preventDefault(); event.currentTarget.focus(); return; } const first = controls[0]; const last = controls.at(-1); if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); } }} onClick={(event) => { const rect = event.currentTarget.getBoundingClientRect(); if (event.target === event.currentTarget && (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom)) cancel(); }} onCancel={(event) => { event.preventDefault(); cancel(); }}><div className={styles.content}><h2 id={heading} className={styles.title}>{title}</h2>{children}</div></dialog>;
}
