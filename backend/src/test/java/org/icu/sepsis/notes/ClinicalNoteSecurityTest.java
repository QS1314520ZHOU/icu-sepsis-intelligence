package org.icu.sepsis.notes;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;import org.springframework.security.core.authority.AuthorityUtils;import org.springframework.security.core.context.SecurityContextHolder;
class ClinicalNoteSecurityTest {
 @Configuration @EnableGlobalMethodSecurity(prePostEnabled=true) static class Security {}
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 UsernamePasswordAuthenticationToken role(String role){var actor=new UsernamePasswordAuthenticationToken("reviewer","",AuthorityUtils.createAuthorityList(role));SecurityContextHolder.getContext().setAuthentication(actor);return actor;}
 @Test void readUsesExistingClinicalRolesAndOnlyDoctorsCanRequestSourceSync(){try(var context=new AnnotationConfigApplicationContext()){
   ClinicalNoteService service=mock(ClinicalNoteService.class);context.registerBean(ClinicalNoteService.class,()->service);ClinicalNoteEvidenceService evidence=mock(ClinicalNoteEvidenceService.class);context.registerBean(ClinicalNoteEvidenceService.class,()->evidence);ClinicalNoteReviewService review=mock(ClinicalNoteReviewService.class);context.registerBean(ClinicalNoteReviewService.class,()->review);context.register(ClinicalNoteReviewController.class,Security.class,ClinicalNoteController.class,ClinicalNoteEvidenceController.class);context.refresh();var controller=context.getBean(ClinicalNoteController.class);clearInvocations(service);
   var reviewController=context.getBean(ClinicalNoteReviewController.class);
   var evidenceController=context.getBean(ClinicalNoteEvidenceController.class);
   var viewer=role("ROLE_VIEWER");assertThrows(AccessDeniedException.class,()->controller.list("p",0,20,viewer));verifyNoInteractions(service,evidence);
   for(String name:new String[]{"ROLE_NURSE","ROLE_QUALITY"}){var actor=role(name);assertNotNull(controller.list("p",0,20,actor));assertThrows(AccessDeniedException.class,()->controller.sync("p",actor));assertThrows(AccessDeniedException.class,()->reviewController.draft("p",null,actor));assertThrows(AccessDeniedException.class,()->evidenceController.prepare("p",null,20,actor));assertThrows(AccessDeniedException.class,()->evidenceController.excerpt("p",new ClinicalNoteEvidenceController.ExcerptRequest(),actor));}
   var doctor=role("ROLE_DOCTOR");assertNotNull(controller.read("p","note",doctor));assertNotNull(controller.sync("p",doctor));verify(service).sync("p","reviewer");assertNotNull(evidenceController.prepare("p",null,20,doctor));verify(evidence).prepare("p",null,20,"reviewer");
 }}
}
