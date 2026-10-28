export function riskText(source: string, value: number | null): string {
  if (source === 'unavailable' || value === null) return '无法评估 · 模型不可用';
  if (source === 'rule_estimate') return `规则分数 ${value}（非概率）`;
  return '模型结果待验证，不显示概率';
}
export function percentage(v: number | null | undefined): string {
  return v == null ? '—（无适用分母）' : `${(v * 100).toFixed(1)}%`;
}
export const labels: Record<string, string> = {
  supported: '支持感染（筛查）',
  possible: '感染可能',
  not_supported: '当前不支持',
  unknown: '信息未知',
  pending: '待审核',
  acknowledged: '已接收',
  confirmed: '已确认',
  rejected: '已驳回',
  deferred: '暂缓待复评',
  closed: '已关闭',
  critical: '紧急筛查',
  high: '优先复核',
  required: '适用',
  conditional: '有条件适用',
  individualized: '个体化',
  not_applicable: '不适用',
  contraindicated: '禁忌',
  review_pending: '待确认适用性',
  not_started: '未开始',
  met: '按时完成',
  met_late: '超时完成',
  completed_before: '触发前完成',
  not_assessed: '未评估目标',
  not_met: '未达目标',
  data_missing: '缺失执行信息',
  hemodynamic_instability: '血流动力学异常',
  infection_with_abnormality: '感染伴异常，需复核',
  unable_to_assess: '信息不足，无法评估',
  not_triggered: '当前未触发',
  suspected_requires_review: '疑似休克筛查，需医生复核',
  incomplete: '关键数据不全',
  sufficient_for_demo_screen: '满足演示筛查输入',
  weight_missing: '无可核验模型权重',
  not_validated: '未完成临床验证',
};
export const text = (s: string | undefined | null) => (s ? labels[s] || s : '—');
