import { CommonModule } from '@angular/common';
import { Component, Input } from '@angular/core';
const paths: Record<string,string[]> = {
  activity:['M3 12h4l3-8 4 16 3-8h4'],
  chart:['M4 4v16h16','M7 15l4-5 4 3 5-7'],
  file:['M14 3H5v18h14V8Z','M14 3v5h5','M8 12h8M8 16h6'],
  bell:['M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9','M10 21h4'],
  clipboard:['M9 4H5v17h14V4h-4','M9 2h6v4H9Z','M8 11l2 2 5-5M8 17h8'],
  refresh:['M20 7v5h-5','M4 17v-5h5','M5 8a8 8 0 0 1 13-3l2 3M19 16a8 8 0 0 1-13 3l-2-3'],
  clock:['M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18','M12 7v5l3 2'],
  info:['M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18','M12 11v6M12 7v1'],
  arrow:['M4 12h16M14 6l6 6-6 6'],
  empty:['M4 5h16v14H4Z','M4 14h5l2 3h2l2-3h5','M8 9h8'],
  search:['M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14','M15 15l6 6'],
  close:['M6 6l12 12M6 18L18 6'],
};
@Component({selector:'ui-icon',standalone:true,imports:[CommonModule],template:`<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path *ngFor="let d of lines" [attr.d]="d"/></svg>`})
export class UiIcon { @Input() name='info'; get lines(){return paths[this.name] || paths['info'];} }
