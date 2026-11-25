import test from 'node:test';
import assert from 'node:assert/strict';
import { hostAccountRole } from '../src/smartcare-protocol.ts';

test('host role hints map clinical staff and keep unknown accounts least privileged', () => {
  assert.equal(hostAccountRole('重症医生'), 'doctor');
  assert.equal(hostAccountRole({displayName:'值班护士'}), 'nurse');
  assert.equal(hostAccountRole('质控科'), 'quality');
  assert.equal(hostAccountRole('unclassified account'), null);
  assert.equal(hostAccountRole(null), null);
});
