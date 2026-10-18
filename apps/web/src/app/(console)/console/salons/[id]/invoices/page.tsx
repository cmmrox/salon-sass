import { ConsolePage } from '@/components/console/ConsolePage';
export default async function Page({ params }: { params: Promise<{ id: string }> }) { const { id } = await params; return <ConsolePage view="salons" id={id} section="invoices" />; }
