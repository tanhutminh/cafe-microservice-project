import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { TranslocoModule } from '@jsverse/transloco';
import { AuthService } from '../../core/auth/auth.service';
import { LanguageSwitcher } from '../../shared/language-switcher/language-switcher';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [MatButtonModule, MatCardModule, TranslocoModule, LanguageSwitcher],
  templateUrl: './home.html',
  styleUrl: './home.scss'
})
export class Home {
  constructor(readonly authService: AuthService) {}

  logout(): void {
    this.authService.logout();
  }
}
