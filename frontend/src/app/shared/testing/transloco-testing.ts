import { TranslocoTestingModule } from '@jsverse/transloco';

/** Minimal Transloco setup for specs: satisfies DI (TranslocoService et al.) and the `transloco` pipe without hitting HTTP for real translation files. */
export function provideTranslocoTesting() {
  return TranslocoTestingModule.forRoot({
    langs: { en: {} },
    translocoConfig: { availableLangs: ['en'], defaultLang: 'en' }
  });
}
