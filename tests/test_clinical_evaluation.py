import importlib.util,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location('evaluation',Path(__file__).resolve().parents[1]/'tools/evaluate_clinical_screening.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class EvaluationTest(unittest.TestCase):
 def row(self,id,label,pred):return dict(case_id=id,target='synthetic-aki',rule_version='test',label=label,prediction=pred)
 def test_unknowns_not_counted_as_negative(self):
  r=m.evaluate([self.row('a','positive','positive'),self.row('b','positive','unknown'),self.row('c','negative','positive')])['groups'][0];self.assertEqual(r['sensitivity_assessed_only'],1);self.assertEqual(r['sensitivity_lower_bound_including_unknown'],.5);self.assertEqual(r['positive_predictive_value'],.5);self.assertFalse(r['clinical_validation_passed'])
 def test_duplicate_case_rejected(self):
  with self.assertRaises(ValueError):m.evaluate([self.row('a','positive','positive')]*2)
 def test_no_data_has_no_fake_rate(self):self.assertEqual(m.evaluate([])['groups'],[])
if __name__=='__main__':unittest.main()
