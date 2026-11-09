package org.icu.sepsis.notes;
import org.springframework.stereotype.Component;import org.springframework.beans.factory.annotation.Value;
@Component
public class BlbcjlProperties {
 @Value("${app.clinical-notes.blbcjl.allow-legacy-body-escaping:true}") public boolean allowLegacyBodyEscaping=true;
 @Value("${app.clinical-notes.blbcjl.endpoint:}") public String endpoint="";
 @Value("${app.clinical-notes.blbcjl.access-key:}") public String accessKey="";
 @Value("${app.clinical-notes.blbcjl.bqdm:}") public String bqdm="";
 @Value("${app.clinical-notes.blbcjl.mbkmc:}") public String mbkmc="";
 @Value("${app.clinical-notes.blbcjl.wjjgmc:}") public String wjjgmc="";
 @Value("${app.clinical-notes.blbcjl.connect-timeout-ms:5000}") public int connectTimeoutMs=5000;
 @Value("${app.clinical-notes.blbcjl.read-timeout-ms:15000}") public int readTimeoutMs=15000;
 @Value("${app.clinical-notes.blbcjl.max-response-bytes:2097152}") public int maxResponseBytes=2097152;
}
