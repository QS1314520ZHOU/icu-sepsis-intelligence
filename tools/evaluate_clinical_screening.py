"""Evaluate de-identified, independently adjudicated outcomes; never infer labels from alert feedback."""
import argparse,csv,json
from collections import defaultdict

def evaluate(rows):
 groups=defaultdict(list);seen=set()
 for row in rows:
  key=(row['target'],row['case_id'])
  if not all(row.get(k) for k in ('target','case_id','rule_version')):raise ValueError('target, case_id and rule_version required')
  if key in seen:raise ValueError('Duplicate case/target; use one independent adjudicated episode')
  seen.add(key)
  if row['label'] not in ('positive','negative') or row['prediction'] not in ('positive','negative','unknown'):raise ValueError('Invalid label or prediction')
  groups[(row['target'],row['rule_version'])].append(row)
 result=[]
 for (target,version),rs in groups.items():
  tp=sum(r['label']=='positive' and r['prediction']=='positive' for r in rs);fp=sum(r['label']=='negative' and r['prediction']=='positive' for r in rs)
  fn=sum(r['label']=='positive' and r['prediction']=='negative' for r in rs);tn=sum(r['label']=='negative' and r['prediction']=='negative' for r in rs)
  up=sum(r['label']=='positive' and r['prediction']=='unknown' for r in rs);un=sum(r['label']=='negative' and r['prediction']=='unknown' for r in rs)
  ratio=lambda a,b:a/b if b else None
  result.append(dict(target=target,rule_version=version,cases=len(rs),tp=tp,fp=fp,fn=fn,tn=tn,unknown_positive=up,unknown_negative=un,coverage=ratio(tp+fp+fn+tn,len(rs)),sensitivity_assessed_only=ratio(tp,tp+fn),sensitivity_lower_bound_including_unknown=ratio(tp,tp+fn+up),positive_predictive_value=ratio(tp,tp+fp),specificity_assessed_only=ratio(tn,tn+fp),clinical_validation_passed=False))
 return {'evaluation_only':True,'groups':result,'note':'Independent outcome adjudication, case sampling and acceptance thresholds require clinical review; unknowns are not negative.'}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('input_csv');p.add_argument('--output',required=True);a=p.parse_args()
 with open(a.input_csv,encoding='utf-8-sig',newline='') as f:out=evaluate(list(csv.DictReader(f)))
 with open(a.output,'w',encoding='utf-8') as f:json.dump(out,f,ensure_ascii=False,indent=2)
 print('Evaluation written; clinical validation is not automatically passed.')
