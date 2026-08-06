import { trimFields } from './form-utils';

interface TestForm {
  name: string;
  unit: string;
  count: number;
  active: boolean;
}

describe('trimFields', () => {
  it('trims leading and trailing whitespace on the given string fields', () => {
    const value: TestForm = { name: '  Coffee  ', unit: ' g ', count: 5, active: true };

    const result = trimFields(value, ['name', 'unit']);

    expect(result.name).toBe('Coffee');
    expect(result.unit).toBe('g');
  });

  it('leaves fields not listed in keys untouched', () => {
    const value: TestForm = { name: '  Coffee  ', unit: ' g ', count: 5, active: true };

    const result = trimFields(value, ['name']);

    expect(result.name).toBe('Coffee');
    expect(result.unit).toBe(' g ');
  });

  it('does not mutate the original object', () => {
    const value: TestForm = { name: '  Coffee  ', unit: 'g', count: 5, active: true };

    trimFields(value, ['name']);

    expect(value.name).toBe('  Coffee  ');
  });

  it('leaves non-string fields untouched even if listed', () => {
    const value: TestForm = { name: 'Coffee', unit: 'g', count: 5, active: true };

    const result = trimFields(value, ['count' as keyof TestForm]);

    expect(result.count).toBe(5);
  });

  it('is a no-op when the strings already have no padding', () => {
    const value: TestForm = { name: 'Coffee', unit: 'g', count: 5, active: true };

    const result = trimFields(value, ['name', 'unit']);

    expect(result).toEqual(value);
  });
});
