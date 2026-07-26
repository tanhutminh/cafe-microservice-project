export interface Category {
  id: number;
  name: string;
  displayOrder: number;
  active: boolean;
}

export interface CategoryRequest {
  name: string;
  displayOrder: number;
  active: boolean;
}
