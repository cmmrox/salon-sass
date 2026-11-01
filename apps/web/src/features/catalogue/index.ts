// The catalogue module's client-safe entry: the Services & pricing page's parts (F05-S01 to F05-S03) and the Tax &
// charges tab's (F05-S03).
export type {
  CatalogueService,
  Category,
  CategoryList,
  PopularMode,
  PopularSetting,
  Product,
  ProductList as ProductListAnswer,
  PublicTax,
  ServiceList,
  TaxHistory,
  TaxSettings,
} from './types';
export { ProductList } from './components/ProductList';
export { ProductSheet } from './components/ProductSheet';
export { TaxSentence } from './components/TaxSentence';
export { TaxHistorySheet, type TaxHistoryState } from './components/TaxHistorySheet';
export { TaxPreview } from './components/TaxPreview';
export { TaxSettingsForm } from './components/TaxSettingsForm';
export {
  newProduct,
  productProblemOfField,
  productProblemsOf,
  productRequestOf,
  productValuesOf,
  sameProduct,
  type ProductProblem,
  type ProductValues,
} from './client/product-form';
export {
  EXAMPLE_PRODUCT,
  EXAMPLE_SERVICE,
  newVatRate,
  previewOf,
  sameTax,
  taxFormOf,
  taxProblemOfField,
  taxProblemsOf,
  taxRequestOf,
  type TaxForm,
  type TaxProblem,
} from './client/tax-form';
export { CategoryRows } from './components/CategoryRows';
export { CategorySheet } from './components/CategorySheet';
export { ConfirmDelete } from './components/ConfirmDelete';
export { PopularCard } from './components/PopularCard';
export { ServiceGroups } from './components/ServiceGroups';
export { ServiceSheet } from './components/ServiceSheet';
export type { PhotoUpload } from './components/PhotoField';
export { categoryProblem, categoryProblemOfField, type CategoryProblem } from './client/category-form';
export {
  newService,
  problemsOf,
  problemsOfFields,
  requestOf,
  sameValues,
  shownProblems,
  valuesOf,
  variantIdRefused,
  type ServiceProblem,
  type ServiceValues,
} from './client/service-form';
