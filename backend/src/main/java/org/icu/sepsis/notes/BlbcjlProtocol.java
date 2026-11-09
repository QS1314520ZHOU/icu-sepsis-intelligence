package org.icu.sepsis.notes;
import java.util.*;import com.fasterxml.jackson.databind.*;
/** Strict schema and attribution; optional bounded compatibility for WBNR control characters and invalid escapes only. */
public final class BlbcjlProtocol {
 public static final String VERSION="blbcjl-body-compat-v2-20261107";
 public static class Failure extends IllegalArgumentException {
  public final String code;public final Map<String,Object> diagnostics;
  public Failure(String code){this(code,Map.of());}
  private Failure(String code,Map<String,Object> diagnostics){super("病程响应校验失败，未保存本批文书");this.code=code;this.diagnostics=Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));}
 }
 private BlbcjlProtocol(){}
 public static ClinicalNoteSource.Batch parse(byte[] bytes,String blh,String bqdm,org.icu.sepsis.domain.Domain.Patient patient,int cap,int bodyLimit) {
  return parse(bytes,blh,bqdm,patient,cap,bodyLimit,false);
 }
 public static ClinicalNoteSource.Batch parse(byte[] bytes,String blh,String bqdm,org.icu.sepsis.domain.Domain.Patient patient,int cap,int bodyLimit,boolean allowLegacyEscaping) {
  try{
   ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
   boolean repaired=false;JsonNode root;
   try{root=json.readTree(bytes);}catch(Exception invalid){
    if(!allowLegacyEscaping)throw failure(invalid,bytes,"strict",false);
    byte[] normalized;
    try{normalized=normalizeLegacyBodyEscapes(bytes);}catch(Failure scan){if("no_compatible_escapes".equals(scan.diagnostics.get("kind")))throw failure(invalid,bytes,"strict",true);Map<String,Object> details=new LinkedHashMap<>(scan.diagnostics);details.put("legacyBodyEscapingEnabled",true);details.put("responseBytes",bytes==null?0:bytes.length);details.put("payloadKind",payloadKind(bytes));throw new Failure("interface_response_invalid",details);}catch(Exception scan){throw failure(scan,bytes,"body_compatibility",true);}
    try{root=json.readTree(normalized);}catch(Exception stillInvalid){var parsed=failure(stillInvalid,normalized,"after_body_compatibility",true);Map<String,Object> details=new LinkedHashMap<>(parsed.diagnostics);details.put("responseBytes",bytes==null?0:bytes.length);throw new Failure("interface_response_invalid",details);}
    repaired=true;
   }if(root!=null&&root.has("code")&&!"0".equals(root.get("code").asText()))throw new Failure("interface_business_error");if(root==null||!root.isObject()||!root.has("code")||!(root.get("code").isTextual()||root.get("code").isIntegralNumber())||!"0".equals(root.get("code").asText())||!root.path("data").isArray())throw new Failure("interface_response_schema_invalid");
   if(root.path("data").size()>cap)throw new Failure("interface_response_too_large");
   var batch=new ClinicalNoteSource.Batch();batch.patientId=patient.id;batch.encounterId=patient.encounterId;batch.department=patient.department;batch.source="BLBCJL";batch.bindingConfirmed=true;batch.complete=null;batch.legacyBodyEscapingNormalized=repaired;
   for(JsonNode row:root.get("data")){
    // All-or-nothing association: never attach a foreign row or silently discard it as a complete response.
    if(!row.isObject()||!row.path("BLH").isTextual()||!blh.equals(row.get("BLH").asText())||!row.path("BQDM").isTextual()||(!bqdm.isEmpty()&&!bqdm.equals(row.get("BQDM").asText()))||!row.path("WBNR").isTextual())throw new Failure("interface_binding_mismatch");
    String body=row.get("WBNR").asText();if(body.isBlank()||body.length()>bodyLimit)throw new Failure("interface_document_invalid");
    var note=new ClinicalNoteSource.Note();note.patientId=patient.id;note.encounterId=patient.encounterId;note.department=patient.department;note.bindingConfirmed=true;note.body=body;
    note.documentType=row.path("MBKMC").isTextual()?row.get("MBKMC").asText():"文书类型待核对";note.candidateReference=row.path("BLXH").isTextual()?row.get("BLXH").asText():"";
    // BLXH uniqueness, ordering, completeness and times were not confirmed by this sample.
    note.identityConfirmed=false;note.documentTypeConfirmed=false;note.availableTimeConfirmed=false;note.versionOrderConfirmed=false;batch.notes.add(note);
   }return batch;
  }catch(Failure failure){throw failure;}catch(Exception ignored){throw new Failure("interface_response_invalid");}
 }
 /** Normalizes literal CR/LF/TAB and unrecognized backslash escapes inside WBNR only. Never repairs quotes, IDs, trailing tokens or schema. */
 static byte[] normalizeLegacyBodyEscapes(byte[] bytes)throws Exception{
  String input=java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
  StringBuilder out=new StringBuilder(input.length()+64);boolean quoted=false,escaped=false,body=false;int changed=0;
  for(int i=0;i<input.length();i++){char c=input.charAt(i);if(!quoted){if(c=='"'){quoted=true;int before=i-1;while(before>=0&&Character.isWhitespace(input.charAt(before)))before--;body=false;if(before>=0&&input.charAt(before)==':'){int end=before-1;while(end>=0&&Character.isWhitespace(input.charAt(end)))end--;int begin=end-5;body=begin>=0&&input.substring(begin,end+1).equals("\"WBNR\"");}}out.append(c);continue;}
   if(escaped){out.append(c);escaped=false;continue;}if(c=='\\'){if(body&&i+1<input.length()&&"\"\\/bfnrtu".indexOf(input.charAt(i+1))<0){out.append("\\\\");changed++;continue;}out.append(c);escaped=true;continue;}if(c=='"'){quoted=false;out.append(c);continue;}
   if(c<32){if(!body||(c!='\n'&&c!='\r'&&c!='\t'))throw scanFailure(input,i,"control_outside_supported_body");out.append(c=='\n'?"\\n":c=='\r'?"\\r":"\\t");changed++;}else out.append(c);
  }if(quoted)throw scanFailure(input,input.length(),"unterminated_string");if(changed==0)throw scanFailure(input,0,"no_compatible_escapes");return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
 }

 private static Failure scanFailure(String input,int index,String kind){
  int line=1,column=1;for(int i=0;i<Math.min(index,input.length());i++){if(input.charAt(i)=='\n'){line++;column=1;}else column++;}
  return new Failure("interface_response_invalid",Map.of("parserVersion",VERSION,"kind",kind,"stage","body_compatibility","line",line,"column",column));
 }
 private static Failure failure(Exception error,byte[] bytes,String stage,boolean compatibility){
  String kind="json_syntax";int line=0,column=0;
  if(error instanceof com.fasterxml.jackson.core.JsonProcessingException){var e=(com.fasterxml.jackson.core.JsonProcessingException)error;String message=Objects.toString(e.getOriginalMessage(),"");
   if(message.contains("Duplicate field"))kind="duplicate_field";
   else if(message.contains("Illegal unquoted character"))kind="unescaped_control";
   else if(message.contains("Unrecognized character escape"))kind="invalid_escape";
   else if(message.contains("Invalid UTF-8")||message.contains("UTF-8"))kind="invalid_encoding";
   else if(message.contains("end-of-input")||message.contains("Unexpected end"))kind="truncated_or_unclosed";
   else if(message.contains("Trailing token"))kind="trailing_tokens";
   else if(message.contains("expecting comma")||message.contains("was expecting comma"))kind="quote_or_separator";
   if(e.getLocation()!=null){line=Math.max(0,e.getLocation().getLineNr());column=Math.max(0,e.getLocation().getColumnNr());}
  }else if(error instanceof java.nio.charset.CharacterCodingException)kind="invalid_encoding";
  Map<String,Object> d=new LinkedHashMap<>();d.put("parserVersion",VERSION);d.put("kind",kind);d.put("stage",stage);d.put("line",line);d.put("column",column);d.put("responseBytes",bytes==null?0:bytes.length);d.put("payloadKind",payloadKind(bytes));d.put("legacyBodyEscapingEnabled",compatibility);
  return new Failure("interface_response_invalid",d);
 }
 private static String payloadKind(byte[] bytes){
  if(bytes==null||bytes.length==0)return "empty";int i=0;if(bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191)i=3;
  while(i<bytes.length&&(bytes[i]==32||bytes[i]==10||bytes[i]==13||bytes[i]==9))i++;
  if(i==bytes.length)return "empty";switch(bytes[i]){case '{':return "json_object";case '[':return "json_array";case '"':return "json_string";case '<':return "markup";default:return "other";}
 }

}
