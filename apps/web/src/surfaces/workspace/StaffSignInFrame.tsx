import type { ReactNode } from 'react';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';
import type { Salon } from '@/features/branding';

/** An image beside the salon's name, which says nothing the name does not: no text alternative. */
const DECORATIVE = '';

/** Public staff entry (CR-012) uses only the salon identity resolved from this request's Host. */
export function StaffSignInFrame({ salon, children }: { salon: Salon; children: ReactNode }) {
  return (
    <main
      className="min-h-svh w-full bg-background bg-(image:--sign-in-interior) bg-cover bg-center bg-no-repeat text-foreground"
      data-testid="staff-sign-in-screen"
    >
      <div className="flex w-full flex-col items-center px-4 pt-(--space-login-top) pb-12 max-login:py-8">
        <div
          className="mb-(--space-login-brand-gap) flex w-full max-w-(--size-login-card) flex-col items-center gap-0.5 text-center max-login:mb-6"
          data-testid="staff-sign-in-brand"
        >
          <Avatar
            className="size-(--size-login-initial) overflow-visible rounded-action border-2 border-primary"
            aria-hidden="true"
            data-testid="staff-sign-in-initial"
          >
            {/* The published logo when there is one (F04-S02), the initial otherwise and while it loads. */}
            {salon.logo && (
              <AvatarImage src={`/brand-images/${salon.logo}`} alt={DECORATIVE} data-testid="staff-sign-in-logo" />
            )}
            <AvatarFallback className="bg-transparent text-login-initial leading-none font-normal text-foreground">
              {Array.from(salon.name.trim())[0]}
            </AvatarFallback>
          </Avatar>
          <p
            className="text-login-name leading-heading tracking-(--letter-spacing-login-name) wrap-anywhere max-login:text-workspace-title"
            data-testid="staff-sign-in-name"
          >
            {salon.name}
          </p>
        </div>
        {children}
      </div>
    </main>
  );
}
