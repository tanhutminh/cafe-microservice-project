export interface RecipeItem {
  ingredientId: number;
  ingredientName: string;
  unit: string;
  quantityRequired: number;
}

export interface RecipeItemRequest {
  ingredientId: number;
  quantityRequired: number;
}
