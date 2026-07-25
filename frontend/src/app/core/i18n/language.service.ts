import { Injectable, inject } from '@angular/core';
import { TranslocoService } from '@jsverse/transloco';

const LANG_STORAGE_KEY = 'cafe.lang';
const SUPPORTED_LANGS = ['vi', 'en'];
const DEFAULT_LANG = 'vi';

@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly transloco = inject(TranslocoService);

  /** Called once at app startup to apply the persisted or browser-detected language. */
  init(): void {
    const stored = localStorage.getItem(LANG_STORAGE_KEY);
    const lang = stored && SUPPORTED_LANGS.includes(stored) ? stored : this.detectBrowserLang();
    this.transloco.setActiveLang(lang);
  }

  setLanguage(lang: string): void {
    if (!SUPPORTED_LANGS.includes(lang)) {
      return;
    }
    this.transloco.setActiveLang(lang);
    localStorage.setItem(LANG_STORAGE_KEY, lang);
  }

  getActiveLang(): string {
    return this.transloco.getActiveLang();
  }

  private detectBrowserLang(): string {
    const browserLang = navigator.language?.slice(0, 2);
    return SUPPORTED_LANGS.includes(browserLang) ? browserLang : DEFAULT_LANG;
  }
}
