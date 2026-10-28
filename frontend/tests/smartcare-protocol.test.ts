import test from 'node:test';
import assert from 'node:assert/strict';
import {
  DEFAULT_MODULE_ID,
  bedOf,
  deptCodeOf,
  extractSmartCareContext,
  genderLabelOf,
  patientIdOf,
} from '../src/smartcare-protocol.ts';

test('extracts SmartCare context and maps fields per host v3', () => {
  const msg = extractSmartCareContext({
    data: {
      type: 'SmartCare',
      patient: {
        id: '6aa54f284978ea2dd8c0ec0c',
        gender: 'Male',
        deptCode2: '3439',
        hisBed: '13',
      },
    },
  } as MessageEvent);
  assert.equal(msg?.type, 'SmartCare');
  assert.equal(patientIdOf(msg?.patient), '6aa54f284978ea2dd8c0ec0c');
  assert.equal(deptCodeOf(msg?.patient), '3439');
  assert.equal(bedOf(msg?.patient), '13');
  assert.equal(genderLabelOf(msg?.patient), '男');
});

test('prefers deptCode over deptCode2 and showBed over hisBed', () => {
  const msg = extractSmartCareContext({
    data: {
      type: 'SmartCare',
      patient: { deptCode: '2915', deptCode2: '3439', showBed: 'E1', hisBed: '13' },
    },
  } as MessageEvent);
  assert.equal(deptCodeOf(msg?.patient), '2915');
  assert.equal(bedOf(msg?.patient), 'E1');
});

test('ignores unrelated messages', () => {
  assert.equal(extractSmartCareContext({ data: { type: 'READY' } } as MessageEvent), null);
  assert.equal(extractSmartCareContext({ data: 'SmartCare' } as MessageEvent), null);
});

test('default navigate module is 病情概览', () => {
  assert.equal(DEFAULT_MODULE_ID, 'doctor-diagnosis-patient-review');
});
