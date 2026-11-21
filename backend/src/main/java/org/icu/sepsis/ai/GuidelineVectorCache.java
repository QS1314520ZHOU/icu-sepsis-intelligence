package org.icu.sepsis.ai;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;

/** Only guideline vectors, never patient facts or queries. Cache identity includes model, endpoint and source revisions. */
final class GuidelineVectorCache {
  private static final ObjectMapper JSON=new ObjectMapper();
  static Map<String,float[]> read(Path dir,String signature,Set<String> ids) throws java.io.IOException {
    Path file=dir.resolve(signature+".json");if(!Files.isRegularFile(file)||Files.size(file)>50_000_000)return null;
    JsonNode root=JSON.readTree(Files.readAllBytes(file));
    if(root.path("schema").asInt()!=1||!signature.equals(root.path("signature").asText()))return null;
    JsonNode values=root.path("vectors");if(!values.isObject()||values.size()!=ids.size())return null;
    var out=new LinkedHashMap<String,float[]>();int dimensions=-1;
    for(String id:ids){JsonNode value=values.path(id);if(!value.isArray()||value.isEmpty()||value.size()>16384)return null;
      if(dimensions<0)dimensions=value.size();if(value.size()!=dimensions)return null;
      float[] vector=new float[dimensions];double norm=0;
      for(int i=0;i<dimensions;i++){if(!value.get(i).isNumber())return null;vector[i]=(float)value.get(i).asDouble();if(!Float.isFinite(vector[i]))return null;norm+=(double)vector[i]*vector[i];}
      if(norm==0)return null;out.put(id,vector);
    }return out;
  }
  static void write(Path dir,String signature,Map<String,float[]> vectors) throws java.io.IOException {
    Files.createDirectories(dir);Path temp=Files.createTempFile(dir,"guidelines-",".tmp");
    try{JSON.writeValue(temp.toFile(),Map.of("schema",1,"signature",signature,"vectors",vectors));
      try{Files.move(temp,dir.resolve(signature+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
      catch(AtomicMoveNotSupportedException e){Files.move(temp,dir.resolve(signature+".json"),StandardCopyOption.REPLACE_EXISTING);}
    }finally{Files.deleteIfExists(temp);}
  }
}
