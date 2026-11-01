import { ConsolePage } from '@/surfaces/console';
export default async function Page({ params }: { params: Promise<{ year: string }> }) {
  const { year } = await params;
  return <ConsolePage view="holidays" id={year} />;
}
