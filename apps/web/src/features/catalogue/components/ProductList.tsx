'use client';
import { formatMoney } from '@salon/format';
import { useTranslations } from 'next-intl';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Empty, EmptyContent, EmptyDescription, EmptyHeader, EmptyTitle } from '@/components/ui/empty';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useIsMobile } from '@/hooks/use-mobile';
import { cn } from '@/lib/utils';
import type { Product, PublicTax } from '../types';

/** The prototype's table (`.tbl`): small muted headings on white, roomier cells, rows ruled in the line colour. */
const HEAD = 'h-12 px-4 text-small font-medium text-muted-foreground';
const CELL = 'px-4 py-3 text-body';

type Props = {
  products: Product[];
  /** The salon's current tax; null before the first save, when VAT is off. */
  tax: PublicTax | null;
  canChange: boolean;
  onNew: () => void;
  onEdit: (product: Product) => void;
};

/**
 * The Products tab's list (F05-S03 AC-7, the F05 block's `prodTab`): each product's name, price, tax ("VAT 18%", "No
 * VAT", or "—" while VAT is off) and "Sold" or "Not sold", as a Table to compare across columns from 900px and as
 * cards below; the prototype's empty state with New product.
 */
export function ProductList({ products, tax, canChange, onNew, onEdit }: Props) {
  const t = useTranslations('services.products');
  const narrow = useIsMobile();
  const vat = tax?.vat ?? null;
  const taxOf = (product: Product) =>
    !vat
      ? t('vatOff')
      : product.chargesVat
        ? t('vatRate', { vat: vat.name, rate: vat.ratePercent })
        : t('noVat', { vat: vat.name });
  const sold = (product: Product) => (
    <Badge variant={product.soldAtCheckout ? 'positive' : 'secondary'} data-testid="product-sold">
      {product.soldAtCheckout ? t('sold') : t('notSold')}
    </Badge>
  );
  const edit = (product: Product) =>
    canChange && (
      <Button
        variant="outline"
        size="sm"
        onClick={() => onEdit(product)}
        aria-label={t('editLabel', { name: product.name })}
        data-testid="product-edit"
      >
        {t('edit')}
      </Button>
    );

  if (products.length === 0)
    return (
      <Empty surface="card" data-testid="products-empty">
        <EmptyHeader>
          <EmptyTitle>{t('emptyTitle')}</EmptyTitle>
          <EmptyDescription>{t('emptyBody')}</EmptyDescription>
        </EmptyHeader>
        {canChange && (
          <EmptyContent>
            <Button size="sm" onClick={onNew} data-testid="products-empty-new">
              {t('emptyAction')}
            </Button>
          </EmptyContent>
        )}
      </Empty>
    );

  if (narrow)
    return (
      <div className="flex flex-col gap-3" data-testid="product-cards">
        {products.map((product) => (
          <Card key={product.id} className="gap-0 p-5" data-testid="product-row" data-id={product.id}>
            <div className="flex items-start justify-between gap-3">
              <h3 className="m-0 min-w-0 text-body font-semibold wrap-anywhere" data-testid="product-name">
                {product.name}
              </h3>
              {sold(product)}
            </div>
            <div className="mt-1.5 mb-2.5 flex flex-wrap items-baseline gap-x-3.5 gap-y-1.5 text-small">
              <span className="font-semibold" data-testid="product-price">
                {formatMoney(product.priceMinor, product.currency)}
              </span>
              <span className="text-muted-foreground" data-testid="product-tax">
                {taxOf(product)}
              </span>
            </div>
            {canChange && <div>{edit(product)}</div>}
          </Card>
        ))}
      </div>
    );

  return (
    <Card className="p-0" data-testid="product-table">
      <Table aria-label={t('table')}>
        <TableHeader>
          <TableRow className="border-border hover:bg-transparent">
            <TableHead className={HEAD}>{t('product')}</TableHead>
            <TableHead className={HEAD}>{t('price')}</TableHead>
            <TableHead className={HEAD}>{t('tax')}</TableHead>
            <TableHead className={HEAD}>{t('atCheckout')}</TableHead>
            {canChange && <TableHead className="sr-only">{t('actions')}</TableHead>}
          </TableRow>
        </TableHeader>
        <TableBody>
          {products.map((product) => (
            <TableRow key={product.id} className="border-border" data-testid="product-row" data-id={product.id}>
              <TableCell className={cn(CELL, 'font-medium wrap-anywhere whitespace-normal')} data-testid="product-name">
                {product.name}
              </TableCell>
              <TableCell className={CELL} data-testid="product-price">
                {formatMoney(product.priceMinor, product.currency)}
              </TableCell>
              <TableCell className={CELL} data-testid="product-tax">
                {taxOf(product)}
              </TableCell>
              <TableCell className={CELL}>{sold(product)}</TableCell>
              {canChange && <TableCell className={cn(CELL, 'text-right')}>{edit(product)}</TableCell>}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </Card>
  );
}
