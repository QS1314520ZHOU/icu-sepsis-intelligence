export class UserMessageError extends Error {}
export function localErrorMessage(error: unknown): string {
  return error instanceof UserMessageError ? error.message : '操作未完成，请刷新并检查数据版本。';
}
