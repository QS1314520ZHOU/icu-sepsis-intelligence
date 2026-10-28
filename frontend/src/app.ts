import { CommonModule } from '@angular/common';
import { Component, inject } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api } from './api';
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, RouterOutlet, RouterLink, RouterLinkActive],
  template: `<ng-container *ngIf="embeddedMode; else standalone"><router-outlet /></ng-container
    ><ng-template #standalone
      ><ng-container *ngIf="api.user; else loggedOut"
        ><header class="topbar">
          <div class="brand">
            <b>ICU</b><span>重症信息系统 <small>／ 脓毒症风险与质控</small></span>
          </div>
          <div class="account">
            <span>{{ api.user }}</span
            ><button (click)="logout()">退出登录</button>
          </div>
        </header>
        <div class="modulebar">
          <span>医生模块</span><strong>脓毒症风险与质控 <small>嵌入验证版 0.3</small></strong
          ><span class="env">模拟 · 不可临床使用</span>
        </div>
        <div class="shell">
          <aside>
            <p class="navtitle">临床工作台</p>
            <nav>
              <a routerLink="/overview" routerLinkActive="active">风险总览</a
              ><a routerLink="/patients" routerLinkActive="active">患者风险列表</a
              ><a routerLink="/alerts" routerLinkActive="active">预警处置中心</a
              ><a routerLink="/bundles" routerLinkActive="active">Bundle 任务管理</a
              ><a
                *ngIf="api.has('DOCTOR') || api.has('QUALITY')"
                routerLink="/quality"
                routerLinkActive="active"
                >质控分析</a
              ><a routerLink="/models" routerLinkActive="active">模型与运行状态</a>
            </nav>
            <div class="sidefoot">
              证据可追溯<br />操作留痕 · 服务端授权<br /><span
                >当前：独立演示容器<br />宿主导航适配尚未接入</span
              >
            </div>
          </aside>
          <main class="content"><router-outlet /></main></div></ng-container
      ><ng-template #loggedOut><router-outlet /></ng-template
    ></ng-template>`,
})
export class App {
  /** 现场以 iframe + SmartCare postMessage 接入时，始终走无顶栏/无登录的 embed 壳。 */
  readonly embeddedMode = isEmbeddedShell();
  api = inject(Api);
  router = inject(Router);
  logout() {
    this.api.logout();
    void this.router.navigateByUrl('/login');
  }
}

function isEmbeddedShell(): boolean {
  if (typeof window === 'undefined') return true;
  const path = window.location.pathname.replace(/\/+$/, '') || '/';
  if (path === '/embed' || path.endsWith('/embed')) return true;
  if (path === '/login') return false;
  try {
    if (window.parent !== window) return true;
  } catch {
    return true;
  }
  return false;
}
