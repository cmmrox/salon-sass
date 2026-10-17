import type { ReactNode } from 'react';
import type { Salon } from '@/lib/api/salon';
import styles from './StaffSignIn.module.css';

/** Public staff entry uses only the salon identity resolved from this request's Host. */
export function StaffSignInFrame({ salon, children }: { salon: Salon; children: ReactNode }) {
  return <main className={styles.screen} data-testid="staff-sign-in-screen">
    <div className={styles.content}>
      <div className={styles.brand} data-testid="staff-sign-in-brand">
        <span className={styles.initial} aria-hidden="true" data-testid="staff-sign-in-initial">{Array.from(salon.name.trim())[0]}</span>
        <p className={styles.name} data-testid="staff-sign-in-name">{salon.name}</p>
      </div>
      {children}
    </div>
  </main>;
}
