import { Component, inject } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { TranslocoService } from '@jsverse/transloco';
import { LanguageService } from '../../core/i18n/language.service';

@Component({
  selector: 'app-language-switcher',
  standalone: true,
  imports: [MatButtonToggleModule],
  templateUrl: './language-switcher.html',
  styleUrl: './language-switcher.scss'
})
export class LanguageSwitcher {
  private readonly languageService = inject(LanguageService);
  private readonly transloco = inject(TranslocoService);

  readonly activeLang = this.transloco.activeLang;

  onLangChange(lang: string): void {
    this.languageService.setLanguage(lang);
  }
}
