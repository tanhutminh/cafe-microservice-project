import { closeIfChanged } from './dialog-utils';

interface TestRequest extends Record<string, unknown> {
  name: string;
  count: number;
}

describe('closeIfChanged', () => {
  it('closes with no result when current equals original', () => {
    const dialogRef = { close: vi.fn() };
    const original: TestRequest = { name: 'A', count: 1 };
    const current: TestRequest = { name: 'A', count: 1 };

    closeIfChanged(dialogRef, original, current);

    expect(dialogRef.close).toHaveBeenCalledWith(undefined);
  });

  it('closes with current when it differs from original', () => {
    const dialogRef = { close: vi.fn() };
    const original: TestRequest = { name: 'A', count: 1 };
    const current: TestRequest = { name: 'B', count: 1 };

    closeIfChanged(dialogRef, original, current);

    expect(dialogRef.close).toHaveBeenCalledWith(current);
  });

  it('closes with current when only a non-string field differs', () => {
    const dialogRef = { close: vi.fn() };
    const original: TestRequest = { name: 'A', count: 1 };
    const current: TestRequest = { name: 'A', count: 2 };

    closeIfChanged(dialogRef, original, current);

    expect(dialogRef.close).toHaveBeenCalledWith(current);
  });

  it('always closes with current when original is null (create mode)', () => {
    const dialogRef = { close: vi.fn() };
    const current: TestRequest = { name: 'A', count: 1 };

    closeIfChanged(dialogRef, null, current);

    expect(dialogRef.close).toHaveBeenCalledWith(current);
  });
});
