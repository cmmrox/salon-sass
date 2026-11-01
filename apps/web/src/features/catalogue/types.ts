import type { components } from '@salon/api-contracts/catalogue';

/** The salon's categories in its order, with the list version a move is made from (F05-S01). */
export type CategoryList = components['schemas']['CatalogueCategoryList'];

/** One category, with how many services it has and its version. */
export type Category = components['schemas']['CatalogueCategory'];

/** Every service of the salon, by name. */
export type ServiceList = components['schemas']['CatalogueServiceList'];

/** One service with its current terms. */
export type CatalogueService = components['schemas']['CatalogueService'];

/** How the Popular list is made, and the list it makes now (F05-S02). */
export type PopularSetting = components['schemas']['CataloguePopularSetting'];

/** "Chosen by you" or "Most booked". */
export type PopularMode = PopularSetting['mode'];

/** The tax settings as settings staff keep them, with what configuration offers now (F05-S03). */
export type TaxSettings = components['schemas']['CatalogueTaxSettings'];

/** A version's settings in the shape a save sends them. */
export type TaxValues = components['schemas']['CatalogueTaxValues'];

/** The salon's current tax as customers may know it; null before the first save. */
export type PublicTax = components['schemas']['CatalogueTax'];

/** A page of the tax settings' versions, newest first. */
export type TaxHistory = components['schemas']['CatalogueTaxHistory'];

/** One of the salon's products (F05-S03). */
export type Product = components['schemas']['CatalogueProduct'];

/** Every product of the salon, by name. */
export type ProductList = components['schemas']['CatalogueProductList'];
