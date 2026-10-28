import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Api, errorMessage } from '../api';
@Component({
  standalone: true,
  imports: [FormsModule, CommonModule],
  template: `<main class="login">
    <div class="brandmark">ICU</div>
    <p class="eyebrow">SEPSIS INTELLIGENCE · DEVELOPMENT</p>
    <h1>重症临床风险工作台</h1>
    <p class="muted">本版本仅用于模拟数据的软件验证，不可用于真实临床决策。</p>
    <form (ngSubmit)="login()">
      <label
        >角色账号<input
          name="user"
          [(ngModel)]="user"
          autocomplete="username"
          required
          placeholder="doctor / nurse / quality" /></label
      ><label
        >登录密码<input
          name="password"
          [(ngModel)]="password"
          type="password"
          autocomplete="current-password"
          required /></label
      ><button class="primary" [disabled]="busy">
        {{ busy ? '正在验证身份…' : '进入工作台' }}
      </button>
    </form>
    <p class="error" role="alert" *ngIf="error">{{ error }}</p>
    <p class="muted small">账号权限由后端校验 · 密码从部署环境配置 · 不存储到浏览器</p>
  </main>`,
})
export class Login {
  api = inject(Api);
  router = inject(Router);
  user = 'doctor';
  password = '';
  busy = false;
  error = '';
  async login() {
    this.busy = true;
    this.error = '';
    try {
      await this.api.login(this.user, this.password);
      this.password = '';
      await this.router.navigateByUrl('/patients');
    } catch (e) {
      this.error = errorMessage(e);
    } finally {
      this.busy = false;
    }
  }
}
