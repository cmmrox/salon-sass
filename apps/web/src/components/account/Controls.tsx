'use client';
import { useTranslations } from 'next-intl';
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react';
import styles from './Account.module.css';
const controlTestId = (name: string) => `account-${name.replace(/([a-z0-9])([A-Z])/g, '$1-$2').toLowerCase()}`;
export function Field({ name, label, error, ...props }: InputHTMLAttributes<HTMLInputElement> & { name: string; label: string; error?: boolean }) {
  const t = useTranslations('account');
  return <label className={styles.field} htmlFor={name}>{label}<input {...props} id={name} name={name} className={styles.input} data-testid={controlTestId(name)} aria-invalid={Boolean(error)} aria-describedby={error ? `${name}-error` : undefined} />{error && <span id={`${name}-error`} className={styles.error} data-testid={`${controlTestId(name)}-error`}>{t('fieldError')}</span>}</label>;
}
export function Button({ children, testId, secondary, danger, ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { testId: string; secondary?: boolean; danger?: boolean }) {
  return <button {...props} className={`${styles.button} ${secondary ? styles.secondary : ''} ${danger ? styles.danger : ''}`} data-testid={testId}>{children}</button>;
}
export function Section({ title, children, signIn = false }: { title: string; children: ReactNode; signIn?: boolean }) { return <section className={`${styles.card} ${signIn ? styles.signIn : ''}`}><h2 className={styles.sectionTitle}>{title}</h2>{children}</section>; }
export function Check({ name, label, checked, onChange, disabled }: { name: string; label: string; checked: boolean; disabled?: boolean; onChange: (checked: boolean) => void }) { return <label className={styles.check}><input type="checkbox" disabled={disabled} data-testid={controlTestId(name)} checked={checked} onChange={(event) => onChange(event.target.checked)} />{label}</label>; }
/** Data still pending after the salon's page is shown; `inline` sits inside a section's card. */
export function AccountLoading({ inline = false }: { inline?: boolean }) { const t = useTranslations('account'); return <div className={inline ? styles.loading : styles.card} role="status" data-testid={inline ? 'account-list-loading' : 'account-loading'}><p>{t(inline ? 'listLoading' : 'loading')}</p>{[1, 2, 3].map((id) => <span key={id} className={styles.skeleton} aria-hidden="true" />)}</div>; }
