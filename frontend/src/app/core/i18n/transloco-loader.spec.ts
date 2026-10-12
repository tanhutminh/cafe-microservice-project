import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Translation } from '@jsverse/transloco';
import { TranslocoHttpLoader } from './transloco-loader';

describe('TranslocoHttpLoader', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it.each(['vi', 'en'])(
    'getTranslation(%s) GETs /i18n/{lang}.json and returns it as-is',
    (lang) => {
      const translation: Translation = { common: { appName: 'Cafe' } };
      let result: Translation | undefined;

      TestBed.inject(TranslocoHttpLoader)
        .getTranslation(lang)
        .subscribe((t) => (result = t));

      const req = httpMock.expectOne(`/i18n/${lang}.json`);
      expect(req.request.method).toBe('GET');
      req.flush(translation);

      expect(result).toEqual(translation);
    },
  );
});
