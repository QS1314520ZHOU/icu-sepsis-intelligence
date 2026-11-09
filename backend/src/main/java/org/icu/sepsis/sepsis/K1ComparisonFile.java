package org.icu.sepsis.sepsis;
import java.nio.file.*;import java.util.*;import com.fasterxml.jackson.databind.*;
/** Local-file-only runner. Never starts Spring or opens database/network connections. */
public final class K1ComparisonFile {
 private K1ComparisonFile(){}
 public static class Input {public String datasetKind;public List<K1WindowComparison.CaseInput> cases;public List<Integer> horizons=List.of(6);}
 public static void run(Path input,Path output) throws Exception {
  if(input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize())||Files.exists(output)||Files.size(input)>20L*1024*1024)throw new IllegalArgumentException("输入或输出路径无效、文件超限或输出已存在");
  ObjectMapper mapper=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  Input data;try{data=mapper.readValue(input.toFile(),Input.class);}catch(Exception e){throw new IllegalArgumentException("输入不符合病例对照格式；请核对字段，不在日志输出病历内容");}
  if(data==null||!Set.of("synthetic","authorized_deidentified_history").contains(Objects.toString(data.datasetKind,"")))throw new IllegalArgumentException("须明确数据为 synthetic 或经授权脱敏的 authorized_deidentified_history");
  Map<String,Object> result=new LinkedHashMap<>(K1WindowComparison.compare(data.cases,data.horizons));result.put("datasetKind",data.datasetKind);result.put("clinicalValidation",false);result.put("formalQualityUse",false);
  Path parent=output.toAbsolutePath().normalize().getParent();Files.createDirectories(parent);Path temp=Files.createTempFile(parent,"k1-local-",".tmp");try{mapper.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(),result);Files.move(temp,output.toAbsolutePath());}finally{Files.deleteIfExists(temp);}
 }
}
