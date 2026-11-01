import { useTranslations } from 'next-intl';
import { Badge } from '@/components/ui/badge';
import { Card, CardTitle } from '@/components/ui/card';
import type { ProfileForm } from '../client/profile-form';

/** The customer's small outline buttons, as the prototype draws them: a picture, so a label. */
const pictureOfAButton = 'min-h-touch border-foreground px-4 text-small';

/**
 * "What customers see" (F04-S01): the salon's name and the profile as it is being typed, laid out as
 * the customer app will show it. Its Call, Chat on WhatsApp and Directions are pictures of the customer's
 * buttons, not controls here, so they are labels; Chat on WhatsApp shows only with a WhatsApp number.
 */
export function ProfilePreview({ salonName, form }: { salonName: string; form: ProfileForm }) {
  const t = useTranslations('settings.profile');
  const notSet = t('previewNotSet');
  const rows: [string, string, string][] = [
    ['address', t('previewAddress'), form.address.trim() || notSet],
    ['phone', t('previewPhone'), form.phone.trim() || notSet],
    ['whatsapp', t('previewWhatsapp'), form.whatsapp.trim() || notSet],
  ];
  return (
    <section aria-labelledby="profile-preview-title" className="flex min-w-0 flex-col gap-3">
      <h2 id="profile-preview-title" className="text-large font-semibold">
        {t('whatCustomersSee')}
      </h2>
      <Card className="gap-3" data-testid="profile-preview">
        <div className="flex min-w-0 flex-col gap-1">
          <CardTitle className="text-body font-semibold wrap-anywhere" data-testid="profile-preview-name">
            {salonName}
          </CardTitle>
          <p className="text-small wrap-anywhere text-muted-foreground" data-testid="profile-preview-description">
            {form.description.trim() || t('previewDescriptionEmpty')}
          </p>
        </div>
        <dl className="flex flex-col">
          {rows.map(([key, label, value]) => (
            <div
              key={key}
              className="flex justify-between gap-4 border-b border-border py-2.5 text-small last:border-0"
            >
              <dt className="text-muted-foreground">{label}</dt>
              <dd className="text-right font-medium wrap-anywhere" data-testid={`profile-preview-${key}`}>
                {value}
              </dd>
            </div>
          ))}
        </dl>
        <ul className="flex flex-wrap gap-1.5" aria-label={t('previewActions')}>
          <li>
            <Badge variant="outline" className={pictureOfAButton}>
              {t('previewCall')}
            </Badge>
          </li>
          {form.whatsapp.trim() && (
            <li>
              <Badge variant="outline" className={pictureOfAButton} data-testid="profile-preview-chat">
                {t('previewChat')}
              </Badge>
            </li>
          )}
          <li>
            <Badge variant="outline" className={pictureOfAButton}>
              {t('previewDirections')}
            </Badge>
          </li>
        </ul>
      </Card>
      <p className="text-small text-foreground">{t('previewNote')}</p>
    </section>
  );
}
