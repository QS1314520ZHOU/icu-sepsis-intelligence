import test from 'node:test';
import assert from 'node:assert/strict';
import { supportedIcu05Schema } from '../src/icu05-view.ts';
test('accepts actual backend schema 3 alongside supported legacy versions',()=>{for(const version of [1,2,3])assert.equal(supportedIcu05Schema(version),true);});
test('does not guess unsupported ICU-05 response contracts',()=>{for(const version of [null,undefined,0,4,'3',{}])assert.equal(supportedIcu05Schema(version),false);});
