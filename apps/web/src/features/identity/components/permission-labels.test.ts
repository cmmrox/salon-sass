import { describe, expect, it } from 'vitest';
import messages from '../../../../messages/en.json';

/**
 * The Account page names each permission the signed-in person holds (AccountScreen), so every permission
 * key the API grants has a label; a missing one shows next-intl's error text. The keys are those of
 * docs/architecture/identity-and-access.md, "Role permissions", the settings pair added by F04 (decision 085) and the catalogue's read added by F05 (decision 093).
 */
const GRANTED = [
  'session.manage',
  'password.change',
  'mfa.enroll',
  'staff.list',
  'staff.manage',
  'audit.list',
  'payment.refund',
  'catalogue.price.change',
  'policy.change',
  'customer.export',
  'calendar.read',
  'customer.read',
  'financial.read',
  'entitlement.read',
  'invoice.read',
  'support.manage',
  'settings.read',
  'settings.manage',
  // F05 (decision 093): reading the service catalogue, which every role holds.
  'catalogue.read',
];

describe('permission labels', () => {
  it.each(GRANTED)('%s has a label', (permission) => {
    const labels = messages.account.permissionLabels as Record<string, string>;
    expect(labels[permission.replaceAll('.', '_')]?.trim()).toBeTruthy();
  });
});
