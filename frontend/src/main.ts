import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { inject } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { CanActivateFn, provideRouter, Router } from '@angular/router';
import { Api, authInterceptor } from './api';
import { App } from './app';
import { EmbedWorkspace } from './embed-workspace';
import { Alerts } from './pages/alerts';
import { Bundles } from './pages/bundles';
import { Detail } from './pages/detail';
import { Login } from './pages/login';
import { Models } from './pages/models';
import { Overview } from './pages/overview';
import { Patients } from './pages/patients';
import { QualityPage } from './pages/qualitypage';
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
      { path: 'embed', component: EmbedWorkspace },
      { path: 'login', component: Login },
      { path: 'overview', component: Overview, canActivate: [authGuard] },
      { path: 'patients', component: Patients, canActivate: [authGuard] },
      { path: 'patients/:id', component: Detail, canActivate: [authGuard] },
      { path: 'alerts', component: Alerts, canActivate: [authGuard] },
      { path: 'bundles', component: Bundles, canActivate: [authGuard] },
      { path: 'quality', component: QualityPage, canActivate: [authGuard, qualityGuard] },
      { path: 'models', component: Models, canActivate: [authGuard] },
      // 现场 iframe：根路径直接进 embed，不出现登录页
      { path: '', pathMatch: 'full', redirectTo: 'embed' },
      { path: '**', redirectTo: 'embed' },
    ]),
  ],
}).catch((e) => console.error('Application bootstrap failed', e));
