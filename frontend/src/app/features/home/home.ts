import { Component } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { AuthService } from '../../core/auth/auth.service';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [MatButtonModule, MatCardModule],
  templateUrl: './home.html',
  styleUrl: './home.scss'
})
export class Home {
  constructor(readonly authService: AuthService) {}

  logout(): void {
    this.authService.logout();
  }
}
