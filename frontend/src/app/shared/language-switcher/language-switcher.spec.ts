import { HarnessLoader } from '@angular/cdk/testing';
import { TestbedHarnessEnvironment } from '@angular/cdk/testing/testbed';
import { TestBed } from '@angular/core/testing';
import { MatButtonToggleHarness } from '@angular/material/button-toggle/testing';
import { TranslocoService } from '@jsverse/transloco';
import { LanguageService } from '../../core/i18n/language.service';
import { provideTranslocoTesting } from '../testing/transloco-testing';
import { LanguageSwitcher } from './language-switcher';

describe('LanguageSwitcher', () => {
  let languageService: { setLanguage: ReturnType<typeof vi.fn> };

  function render(): HarnessLoader {
    languageService = { setLanguage: vi.fn() };
    TestBed.configureTestingModule({
      imports: [provideTranslocoTesting()],
      providers: [{ provide: LanguageService, useValue: languageService }],
    });
    return TestbedHarnessEnvironment.loader(TestBed.createComponent(LanguageSwitcher));
  }

  function toggle(loader: HarnessLoader, label: 'VI' | 'EN'): Promise<MatButtonToggleHarness> {
    return loader.getHarness(MatButtonToggleHarness.with({ text: label }));
  }

  it("marks Transloco's active language as the selected toggle", async () => {
    const loader = render();

    expect(TestBed.inject(TranslocoService).getActiveLang()).toBe('en');
    expect(await (await toggle(loader, 'EN')).isChecked()).toBe(true);
    expect(await (await toggle(loader, 'VI')).isChecked()).toBe(false);
  });

  it('follows Transloco when the active language changes after rendering', async () => {
    const loader = render();

    TestBed.inject(TranslocoService).setActiveLang('vi');

    expect(await (await toggle(loader, 'VI')).isChecked()).toBe(true);
    expect(await (await toggle(loader, 'EN')).isChecked()).toBe(false);
  });

  it('asks the language service to switch when another language is picked', async () => {
    await (await toggle(render(), 'VI')).check();

    expect(languageService.setLanguage).toHaveBeenCalledWith('vi');
  });
});
