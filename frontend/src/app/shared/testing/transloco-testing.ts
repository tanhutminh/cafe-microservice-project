import { Translation, TranslocoTestingModule } from '@jsverse/transloco';

/**
 * Minimal Transloco setup for specs: satisfies DI (TranslocoService et al.) and the `transloco`
 * pipe without hitting HTTP for real translation files. A key missing from `en` renders as the
 * key itself, so pass translations only when a spec checks the rendered text.
 */
export function provideTranslocoTesting(en: Translation = {}) {
  return TranslocoTestingModule.forRoot({
    langs: { en },
    translocoConfig: { availableLangs: ['en'], defaultLang: 'en' },
    preloadLangs: true,
  });
}
