/** Sanitized parser metadata only. Never accepts or renders a server exception or response excerpt. */
export interface NoteParseDiagnostics {kind?:string;stage?:string;line?:number;column?:number;responseBytes?:number;legacyBodyEscapingEnabled?:boolean;parserVersion?:string;payloadKind?:string;}
export interface NoteAdapterDiagnostics {parserVersion?:string;legacyBodyEscapingEnabled?:boolean;}
export function parserVersion(value?:string){return value&&/^[a-zA-Z0-9._-]{1,80}$/.test(value)?value:'版本未提供';}
export function noteParseDetail(d:NoteParseDiagnostics){
 const kind:Record<string,string>={unescaped_control:'存在未转义换行或控制字符',invalid_escape:'存在非法反斜杠转义',invalid_encoding:'响应编码不是有效UTF-8',truncated_or_unclosed:'响应被截断或字符串未闭合',unterminated_string:'字符串未闭合',duplicate_field:'存在重复JSON字段',trailing_tokens:'JSON结束后还有额外内容',quote_or_separator:'引号或字段分隔符不合法',control_outside_supported_body:'正文外存在控制字符，或正文包含尚不支持的控制符',json_syntax:'JSON语法不合法'};
 const stages:Record<string,string>={strict:'原始响应',body_compatibility:'正文转义检查',after_body_compatibility:'正文兼容处理后'};
 let message=kind[d.kind||'']||'JSON格式仍需核对';if(typeof d.line==='number'&&Number.isInteger(d.line)&&d.line>0&&typeof d.column==='number'&&Number.isInteger(d.column)&&d.column>0)message+=' · 第'+d.line+'行，第'+d.column+'列';
 if(stages[d.stage||''])message+=' · '+stages[d.stage!];if(typeof d.legacyBodyEscapingEnabled==='boolean')message+=' · 此次解析兼容'+(d.legacyBodyEscapingEnabled?'开启':'关闭');if(d.payloadKind==='markup')message+=' · 响应看起来是HTML或XML';else if(d.payloadKind==='json_string')message+=' · 响应外层是字符串';else if(d.payloadKind==='empty')message+=' · 响应为空';return message;
}
