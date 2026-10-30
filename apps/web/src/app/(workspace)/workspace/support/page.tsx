import { headers } from 'next/headers';
import { notFound } from 'next/navigation';
import { BrandStyle } from '@/components/shared/BrandStyle';
import { StaffSignInFrame } from '@/surfaces/workspace/StaffSignInFrame';
import { SupportEntry } from '@/surfaces/workspace/SupportEntry';
import { decodeSalon, SALON_HEADER } from '@/features/branding/server/salon-header';

/** The support entry page (F03-S04): the console's link opens it with the entry code in its fragment. */
export default async function Page() {
  const salon = decodeSalon((await headers()).get(SALON_HEADER));
  if (!salon) notFound();
  return <><BrandStyle colour={salon.brandColour} /><StaffSignInFrame salon={salon}><SupportEntry /></StaffSignInFrame></>;
}
