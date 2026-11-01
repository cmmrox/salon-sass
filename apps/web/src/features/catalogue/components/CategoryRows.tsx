'use client';
import { ArrowDown, ArrowUp } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemTitle } from '@/components/ui/item';
import type { Category } from '../types';

type Props = {
  categories: Category[];
  canChange: boolean;
  busy: boolean;
  onMove: (category: Category, direction: 'up' | 'down') => void;
  onRename: (category: Category) => void;
  onDelete: (category: Category) => void;
};

/**
 * The Categories tab (F05-S01 AC-2, the prototype's `catTab`): each category in the salon's order with how many
 * services it has, and ↑, ↓, Rename and Delete for those who may change the catalogue. The first cannot move up
 * and the last cannot move down.
 */
export function CategoryRows({ categories, canChange, busy, onMove, onRename, onDelete }: Props) {
  const t = useTranslations('services.categories');
  return (
    <Card className="p-5 py-2" data-testid="category-list">
      <ItemGroup>
        {categories.map((category, index) => (
          <Item
            key={category.id}
            variant="divided"
            className="flex-wrap gap-2.5 px-0"
            data-testid="category-row"
            data-id={category.id}
          >
            <ItemContent className="min-w-44 basis-44">
              <ItemTitle className="wrap-anywhere" data-testid="category-row-name">
                {category.name}
              </ItemTitle>
              <ItemDescription data-testid="category-row-count">
                {t('count', { count: category.serviceCount })}
              </ItemDescription>
            </ItemContent>
            {canChange && (
              <ItemActions className="flex-wrap gap-1.5">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy || index === 0}
                  onClick={() => onMove(category, 'up')}
                  aria-label={t('up', { name: category.name })}
                  data-testid="category-row-up"
                >
                  <ArrowUp aria-hidden="true" />
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy || index === categories.length - 1}
                  onClick={() => onMove(category, 'down')}
                  aria-label={t('down', { name: category.name })}
                  data-testid="category-row-down"
                >
                  <ArrowDown aria-hidden="true" />
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  onClick={() => onRename(category)}
                  aria-label={t('renameLabel', { name: category.name })}
                  data-testid="category-row-rename"
                >
                  {t('rename')}
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  onClick={() => onDelete(category)}
                  aria-label={t('deleteLabel', { name: category.name })}
                  data-testid="category-row-delete"
                >
                  {t('delete')}
                </Button>
              </ItemActions>
            )}
          </Item>
        ))}
      </ItemGroup>
    </Card>
  );
}
