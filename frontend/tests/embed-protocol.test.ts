import test from 'node:test';
import assert from 'node:assert/strict';
import { trustedMessage } from '../src/embed-protocol.ts';
const parent = {};
const channel = '12345678-1234-1234-1234-123456789012';
const message = {
  namespace: 'icu.sepsis',
  version: 1,
  type: 'SET_CONTEXT',
  channel,
  requestId: 'request-0001',
  payload: {
    code: 'opaque-once-only',
    patientId: 'demo-normal',
    encounterId: 'demo-normal-enc-001',
  },
};
function read(
  data: unknown = message,
  origin = 'https://icu.example.test',
  source: unknown = parent,
) {
  return trustedMessage({ data, origin, source }, parent, ['https://icu.example.test'], channel);
}
test('accept exact parent window, origin, channel and supported schema', () =>
  assert.equal(read()?.type, 'SET_CONTEXT'));
test('reject other origin even when payload looks valid', () =>
  assert.equal(read(message, 'https://evil.example.test'), null));
test('reject sibling frame source', () =>
  assert.equal(read(message, 'https://icu.example.test', {}), null));
test('reject wrong channel and old protocol', () => {
  assert.equal(read({ ...message, channel: 'other-channel' }), null);
  assert.equal(read({ ...message, version: 2 }), null);
});
test('roles and tokens cannot be smuggled into context payload', () =>
  assert.equal(read({ ...message, payload: { ...message.payload, roles: ['DOCTOR'] } }), null));
test('reject oversized or incomplete identifiers', () => {
  assert.equal(
    read({ ...message, payload: { ...message.payload, patientId: 'x'.repeat(129) } }),
    null,
  );
  assert.equal(read({ ...message, payload: { patientId: 'x' } }), null);
});
test('explicit clear-context has no patient data requirements', () =>
  assert.equal(
    read({ ...message, type: 'CLEAR_CONTEXT', payload: undefined })?.type,
    'CLEAR_CONTEXT',
  ));
