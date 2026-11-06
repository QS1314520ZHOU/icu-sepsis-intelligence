package org.icu.sepsis.notes;
import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.time.Instant;import java.util.Map;import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
class ClinicalNoteEvidenceRoutingTest {
 @Test void literalEvidencePathDoesNotResolveAsSnapshotId()throws Exception{
  var notes=mock(ClinicalNoteService.class);var evidence=mock(ClinicalNoteEvidenceService.class);when(evidence.prepare(anyString(),any(),anyInt(),anyString())).thenReturn(Map.of("modelInvoked",false));
  var mvc=MockMvcBuilders.standaloneSetup(new ClinicalNoteController(notes),new ClinicalNoteEvidenceController(evidence)).build();
  mvc.perform(get("/api/v1/patients/p/clinical-notes/review-evidence").param("asOf","2026-11-06T03:00:00Z").param("limit","5").principal(new UsernamePasswordAuthenticationToken("doctor","")))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.modelInvoked").value(false));
  verify(evidence).prepare("p",Instant.parse("2026-11-06T03:00:00Z"),5,"doctor");verifyNoInteractions(notes);
 }

 @Test void excerptRequiresExplicitFrozenTimeAndPacketFingerprint()throws Exception{
  var notes=mock(ClinicalNoteService.class);var evidence=mock(ClinicalNoteEvidenceService.class);when(evidence.excerpt(anyString(),anyString(),any(),anyInt(),anyInt(),anyString(),anyString())).thenReturn(Map.of("ref","N1","modelInvoked",false));
  var mvc=MockMvcBuilders.standaloneSetup(new ClinicalNoteController(notes),new ClinicalNoteEvidenceController(evidence)).build();var actor=new UsernamePasswordAuthenticationToken("doctor","");
  mvc.perform(post("/api/v1/patients/p/clinical-notes/review-evidence/excerpt").principal(actor).contentType("application/json").content("{\"snapshotId\":\"a\",\"asOf\":\"2026-11-06T03:00:00Z\",\"start\":0,\"end\":4,\"expectedFingerprint\":\"synthetic-token\"}"))
    .andExpect(status().isOk()).andExpect(jsonPath("$.data.ref").value("N1"));
  verify(evidence).excerpt("p","a",Instant.parse("2026-11-06T03:00:00Z"),0,4,"synthetic-token","doctor");clearInvocations(evidence);
  mvc.perform(post("/api/v1/patients/p/clinical-notes/review-evidence/excerpt").principal(actor).contentType("application/json").content("{\"snapshotId\":\"a\",\"start\":0,\"end\":4}"))
    .andExpect(status().isBadRequest());verifyNoInteractions(evidence,notes);
 }
}
