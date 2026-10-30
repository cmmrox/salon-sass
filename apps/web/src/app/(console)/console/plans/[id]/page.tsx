import { ConsolePage } from '@/surfaces/console/ConsolePage';
export default async function Page({ params }: { params: Promise<{ id: string }> }) { const { id } = await params; return <ConsolePage view="plans" id={id} />; }
