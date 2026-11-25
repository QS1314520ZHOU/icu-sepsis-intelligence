export interface BatchRow { state:'pending'|'loading'|'done'|'failed'; error?:string; }

/** Bounded parallel scan shared by the department roster and its failed-only retry. */
export async function scanDepartmentBatch<T extends BatchRow>(
  targets:T[],
  load:(row:T)=>Promise<void>,
  afterEach:()=>void,
  concurrency=4,
):Promise<void>{
  let cursor=0;
  const workers=Array.from({length:Math.min(Math.max(1,concurrency),targets.length)},async()=>{
    while(cursor<targets.length){
      const row=targets[cursor++];
      row.state='loading';row.error='';
      try{await load(row);row.state='done';}
      catch(error){row.state='failed';row.error=error instanceof Error?error.message:String(error);}
      afterEach();
    }
  });
  await Promise.all(workers);
}
