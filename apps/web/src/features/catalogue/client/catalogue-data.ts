'use client';
import { moduleData } from '@/lib/api-core/data-layer';
import { workspaceSession, type WorkspaceKind } from '@/lib/api-core/request';
import type {
  CatalogueService,
  CategoryList,
  PopularSetting,
  Product,
  ProductList,
  PublicTax,
  ServiceList,
  TaxHistory,
  TaxSettings,
} from '../types';

/** The catalogue's BFF reads (F05-S01 to F05-S03), each with its query and answer. */
type CatalogueReads = {
  'catalogue-categories': { answer: CategoryList };
  'catalogue-services': { answer: ServiceList };
  'catalogue-popular-setting': { answer: PopularSetting };
  'catalogue-products': { answer: ProductList };
  'catalogue-tax-settings': { answer: TaxSettings };
  'catalogue-tax-history': { query: { page: number }; answer: TaxHistory };
  /** The salon's current tax as customers may know it; null before the first save. */
  'catalogue-tax-public': { answer: { current: PublicTax | null } };
};

type Body = Record<string, unknown>;
type Done = { ok: true };

/** The catalogue's BFF changes, each with what it answers. The request is rebuilt on the server from its own fields. */
type CatalogueChanges = {
  'catalogue-category-add': { body: Body; answer: unknown };
  'catalogue-category-rename': { body: Body; answer: unknown };
  'catalogue-category-move': { body: Body; answer: CategoryList };
  'catalogue-category-delete': { body: Body; answer: Done };
  'catalogue-service-create': { body: Body; answer: CatalogueService };
  'catalogue-service-save': { body: Body; answer: CatalogueService };
  'catalogue-service-duplicate': { body: Body; answer: CatalogueService };
  'catalogue-service-online': { body: Body; answer: CatalogueService };
  'catalogue-service-delete': { body: Body; answer: Done };
  'catalogue-service-popular': { body: Body; answer: CatalogueService };
  'catalogue-popular-mode': { body: Body; answer: PopularSetting };
  'catalogue-photo': { body: FormData; answer: CatalogueService };
  'catalogue-photo-remove': { body: Body; answer: CatalogueService };
  'catalogue-product-add': { body: Body; answer: Product };
  'catalogue-product-save': { body: Body; answer: Product };
  'catalogue-product-delete': { body: Body; answer: Done };
  'catalogue-tax-save': { body: Body; answer: TaxSettings };
};

const catalogue = moduleData<CatalogueReads, CatalogueChanges>('catalogue');

/** The salon's categories, in its order. */
export const useCategories = (kind: WorkspaceKind) => catalogue.useRead(workspaceSession(kind), 'catalogue-categories');

/** The salon's services, every category's. */
export const useServices = (kind: WorkspaceKind) => catalogue.useRead(workspaceSession(kind), 'catalogue-services');

/** How the popular services are chosen. */
export const usePopularSetting = (kind: WorkspaceKind) =>
  catalogue.useRead(workspaceSession(kind), 'catalogue-popular-setting');

/** The products sold at the front desk (F05-S03). */
export const useProductList = (kind: WorkspaceKind) => catalogue.useRead(workspaceSession(kind), 'catalogue-products');

/** The salon's tax settings, as the Tax & charges tab edits them. */
export const useTaxSettings = (kind: WorkspaceKind) =>
  catalogue.useRead(workspaceSession(kind), 'catalogue-tax-settings');

/** The salon's current tax as customers may know it. */
export const usePublicTax = (kind: WorkspaceKind) => catalogue.useRead(workspaceSession(kind), 'catalogue-tax-public');

/** One page of the tax's history, read only while it is asked for. */
export const useTaxHistory = (kind: WorkspaceKind, page: number, enabled: boolean) =>
  catalogue.useRead(workspaceSession(kind), 'catalogue-tax-history', { page }, { enabled });

/** The catalogue read when something happens elsewhere (New appointment opened), through the same cache and keys. */
export const useCatalogueAsk = (kind: WorkspaceKind) => catalogue.useAsk(workspaceSession(kind));

/** Puts a change's answer in place of a read's. */
export const useSetCatalogueAnswer = (kind: WorkspaceKind) => catalogue.useSetAnswer(workspaceSession(kind));

/** The catalogue's changes, each made once and typed by its action. */
export const useCatalogueChanges = (kind: WorkspaceKind) => catalogue.useChanges(workspaceSession(kind));
