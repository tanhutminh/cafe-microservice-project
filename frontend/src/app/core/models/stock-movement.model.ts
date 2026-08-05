export interface StockMovement {
  id: number;
  changeAmount: number;
  reason: string;
  referenceId: string | null;
  createdAt: string;
}
