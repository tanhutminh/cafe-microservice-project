export type Role = 'ADMIN' | 'CASHIER';

export interface User {
  id: number;
  username: string;
  fullName: string;
  role: Role;
  active: boolean;
}
