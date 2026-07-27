export type TableStatus = 'AVAILABLE' | 'OCCUPIED';

export interface DiningTable {
  id: number;
  tableNumber: string;
  capacity: number;
  status: TableStatus;
  active: boolean;
}
