'use client';
import { useEffect, useId, useRef, type ReactNode } from 'react';
import styles from './Confirmation.module.css';
/** Native modal supplies inert background, focus containment and keyboard dismissal. */
export function Confirmation({ title, children, onCancel, workspace = false }: { title: string; children: ReactNode; workspace?: boolean; onCancel: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const heading = useId();
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const node = dialog.current;
    node?.showModal();
    node?.querySelector<HTMLButtonElement>('[data-testid="account-cancel-action"]')?.focus();
    return () => { node?.close(); previous?.focus(); };
  }, []);
  return <dialog ref={dialog} className={`${styles.dialog} ${workspace ? styles.workspace : ''}`} aria-labelledby={heading} aria-modal="true" data-testid="account-confirmation" onCancel={(event) => { event.preventDefault(); onCancel(); }}><div className={styles.content}><h2 id={heading} className={styles.title}>{title}</h2>{children}</div></dialog>;
}
