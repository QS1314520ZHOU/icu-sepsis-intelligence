import test from 'node:test';
import assert from 'node:assert/strict';
import {decisionRetryDelay, transientDecisionError, transientDecisionStatus} from '../src/ai-decision-retry.ts';
test('automatic retries back off and terminate after three attempts', () => {
  assert.deepEqual([0,1,2,3,4].map(decisionRetryDelay), [30000,60000,120000,null,null]);
});
test('temporary transport and gateway failures can recover automatically', () => {
  for (const status of [0,408,429,500,502,503,504]) assert.equal(transientDecisionError({status}),true);
  assert.equal(transientDecisionStatus('busy'),true);
  assert.equal(transientDecisionStatus('unavailable'),true);
});
test('authorization, context, malformed output and programming errors never auto retry', () => {
  for (const status of [400,401,403,404,409,422]) assert.equal(transientDecisionError({status}),false);
  for (const error of [null,undefined,new Error('patient mismatch'),{status:'503'}]) assert.equal(transientDecisionError(error),false);
  for (const status of ['ok','disabled','invalid_output']) assert.equal(transientDecisionStatus(status),false);
});
