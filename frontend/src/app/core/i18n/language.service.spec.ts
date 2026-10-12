import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { LanguageService } from './language.service';

describe('LanguageService', () => {
  const LANG_STORAGE_KEY = 'cafe.lang';

  let transloco: {
    setActiveLang: ReturnType<typeof vi.fn>;
    getActiveLang: ReturnType<typeof vi.fn>;
  };

  function createService(browserLang?: string): LanguageService {
    vi.spyOn(navigator, 'language', 'get').mockReturnValue(browserLang as string);
    transloco = { setActiveLang: vi.fn(), getActiveLang: vi.fn().mockReturnValue('en') };
    TestBed.configureTestingModule({
      providers: [{ provide: TranslocoService, useValue: transloco }],
    });
    return TestBed.inject(LanguageService);
  }

  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  describe('init()', () => {
    it.each([
      ['vi', 'en-US'],
      ['en', 'vi-VN'],
    ])('applies the stored language %s over the browser language %s', (stored, browserLang) => {
      localStorage.setItem(LANG_STORAGE_KEY, stored);

      createService(browserLang).init();

      expect(transloco.setActiveLang).toHaveBeenCalledWith(stored);
    });

    it('ignores an unsupported stored language and falls back to the browser language', () => {
      localStorage.setItem(LANG_STORAGE_KEY, 'fr');

      createService('en-GB').init();

      expect(transloco.setActiveLang).toHaveBeenCalledWith('en');
    });

    it.each([
      ['en-US', 'en'],
      ['vi-VN', 'vi'],
      ['en', 'en'],
    ])(
      'with nothing stored, uses the supported browser language %s as %s',
      (browserLang, expected) => {
        createService(browserLang).init();

        expect(transloco.setActiveLang).toHaveBeenCalledWith(expected);
      },
    );

    it.each([['fr-FR'], [''], [undefined]])(
      'with nothing stored, falls back to vi when the browser language is %j',
      (browserLang) => {
        createService(browserLang).init();

        expect(transloco.setActiveLang).toHaveBeenCalledWith('vi');
      },
    );

    it('does not persist the language it detected', () => {
      createService('en-US').init();

      expect(localStorage.getItem(LANG_STORAGE_KEY)).toBeNull();
    });
  });

  describe('setLanguage()', () => {
    it.each(['vi', 'en'])('activates and stores the supported language %s', (lang) => {
      createService().setLanguage(lang);

      expect(transloco.setActiveLang).toHaveBeenCalledWith(lang);
      expect(localStorage.getItem(LANG_STORAGE_KEY)).toBe(lang);
    });

    it('ignores an unsupported language', () => {
      localStorage.setItem(LANG_STORAGE_KEY, 'vi');

      createService().setLanguage('fr');

      expect(transloco.setActiveLang).not.toHaveBeenCalled();
      expect(localStorage.getItem(LANG_STORAGE_KEY)).toBe('vi');
    });
  });

  it("getActiveLang() returns Transloco's active language", () => {
    expect(createService().getActiveLang()).toBe('en');
  });
});
