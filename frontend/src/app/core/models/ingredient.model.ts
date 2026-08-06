export interface Ingredient {
  id: number;
  name: string;
  unit: string;
  currentStock: number;
  minStock: number;
  reservedQuantity: number;
  lowStock: boolean;
  active: boolean;
}

export interface IngredientRequest {
  name: string;
  unit: string;
  minStock: number;
  active: boolean;
}

export interface StockInRequest {
  quantity: number;
}
