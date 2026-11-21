import test from 'node:test';
import assert from 'node:assert/strict';
import {UserMessageError,localErrorMessage} from '../src/user-message-error.ts';
test('explicit form and context validation messages retain the actionable reason',()=>{assert.equal(localErrorMessage(new UserMessageError('RASS需要观察时间和来源')),'RASS需要观察时间和来源');});
test('unexpected exception details are not displayed to clinical users',()=>{for(const error of [new Error('internal-secret'),new TypeError('internal-stack'),null,{message:'raw-secret'}])assert.equal(localErrorMessage(error),'操作未完成，请刷新并检查数据版本。');});
