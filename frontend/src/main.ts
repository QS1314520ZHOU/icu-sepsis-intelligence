import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { inject } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { CanActivateFn, provideRouter, Router } from '@angular/router';
import { Api, authInterceptor } from './api';
import { App } from './app';
const authGuard: CanActivateFn = () => {
  const api = inject(Api);
  return !!api.user || inject(Router).createUrlTree(['/login']);
};
const qualityGuard: CanActivateFn = () => {
  const api = inject(Api);
  return api.has('DOCTOR') || api.has('QUALITY') || inject(Router).createUrlTree(['/patients']);
};
bootstrapApplication(App, {
  providers: [
    provideHttpClient(withInterceptors([authInterceptor])),
    provideRouter([
      { path: 'embed', loadComponent: () => import('./embed-workspace').then(m => m.EmbedWorkspace) },
      { path: 'login', loadComponent: () => import('./pages/login').then(m => m.Login) },
      { path: 'overview', loadComponent: () => import('./pages/overview').then(m => m.Overview), canActivate: [authGuard] },
      { path: 'patients', loadComponent: () => import('./pages/patients').then(m => m.Patients), canActivate: [authGuard] },
      { path: 'patients/:id', loadComponent: () => import('./pages/detail').then(m => m.Detail), canActivate: [authGuard] },
      { path: 'alerts', loadComponent: () => import('./pages/alerts').then(m => m.Alerts), canActivate: [authGuard] },
      { path: 'bundles', loadComponent: () => import('./pages/bundles').then(m => m.Bundles), canActivate: [authGuard] },
      { path: 'quality', loadComponent: () => import('./pages/qualitypage').then(m => m.QualityPage), canActivate: [authGuard, qualityGuard] },
      { path: 'models', loadComponent: () => import('./pages/models').then(m => m.Models), canActivate: [authGuard] },
      // 现场 iframe：根路径直接进 embed，不出现登录页
      { path: '', pathMatch: 'full', redirectTo: 'embed' },
      { path: '**', redirectTo: 'embed' },
    ]),
  ],
}).catch((e) => console.error('Application bootstrap failed', e));
