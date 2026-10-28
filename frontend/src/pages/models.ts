import { CommonModule } from '@angular/common';
import { Component, inject, OnInit } from '@angular/core';
import { Api, errorMessage, Model } from '../api';
import { text } from '../presentation';
@Component({
  standalone: true,
  imports: [CommonModule],
  template: `<div class="pagehead">
      <div>
        <p class="eyebrow">MODEL SAFETY & AVAILABILITY</p>
        <h1>模型与运行状态</h1>
        <p class="muted">能加载模型不代表具备临床预测能力。</p>
      </div>
      <button (click)="load()">刷新状态</button>
    </div>
    <p class="error" *ngIf="error">{{ error }}</p>
    <section class="card" *ngIf="m && !error">
      <span class="tag warning">模型不可用</span>
      <h2>未启用真实模型推理</h2>
      <p>{{ m.reason }}</p>
      <dl>
        <dt>模型状态</dt>
        <dd>{{ text(m.modelStatus) }}</dd>
        <dt>输出来源</dt>
        <dd>{{ m.predictionSource }}</dd>
        <dt>验证状态</dt>
        <dd>{{ text(m.localValidationStatus) }}</dd>
        <dt>真实模型运行</dt>
        <dd>否</dd>
        <dt>允许临床使用</dt>
        <dd>否</dd>
      </dl>
      <div class="notice">
        没有权重或验证数据时，不生成准确率、AUROC、校准曲线或风险百分比。规则筛查与预警、Bundle
        工作流可以独立运行。
      </div>
    </section>`,
})
export class Models implements OnInit {
  api = inject(Api);
  m: Model | null = null;
  error = '';
  text = text;
  ngOnInit() {
    void this.load();
  }
  async load() {
    this.error = '';
    try {
      this.m = await this.api.get<Model>('/models/status');
    } catch (e) {
      this.error = errorMessage(e);
    }
  }
}
