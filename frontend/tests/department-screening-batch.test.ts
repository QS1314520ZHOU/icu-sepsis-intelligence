import test from 'node:test';
import assert from 'node:assert/strict';
import {scanDepartmentBatch} from '../src/department-screening-batch.ts';

test('department batch caps concurrency and retry operates on failed rows only',async()=>{
  const rows=Array.from({length:9},(_,i)=>({id:i,state:'pending' as const,error:''}));
  let active=0,maxActive=0,checked=0;const calls=new Map<number,number>();
  const load=async(row:typeof rows[number])=>{active++;maxActive=Math.max(active,maxActive);calls.set(row.id,(calls.get(row.id)||0)+1);await new Promise(resolve=>setTimeout(resolve,2));active--;if(row.id===3&&calls.get(row.id)===1)throw new Error('临时超时');};
  await scanDepartmentBatch(rows,load,()=>checked++,4);
  assert.equal(maxActive,4);assert.equal(checked,9);assert.equal(rows[3].state,'failed');assert.equal(rows.filter(row=>row.state==='done').length,8);
  const failed=rows.filter(row=>row.state==='failed');checked=0;await scanDepartmentBatch(failed,load,()=>checked++,4);
  assert.deepEqual(failed.map(row=>row.state),['done']);assert.equal(checked,1);assert.equal(calls.get(3),2);assert.equal(rows.filter(row=>row.state==='done').length,9);
});
