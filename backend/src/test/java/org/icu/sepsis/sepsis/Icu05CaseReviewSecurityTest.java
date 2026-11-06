package org.icu.sepsis.sepsis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import org.icu.sepsis.api.Requests;
import org.icu.sepsis.application.Store;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class Icu05CaseReviewSecurityTest {
  @Configuration @EnableGlobalMethodSecurity(prePostEnabled=true) static class Security {}
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  @Test void doctorAndQualityCanReviewButNurseCannotWriteOrReadHistory() {
    try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
      Icu05CaseReviewService service=mock(Icu05CaseReviewService.class);
      Icu05OverrideService overrides=mock(Icu05OverrideService.class);
      context.registerBean(Store.class,()->mock(Store.class));
      context.registerBean(Icu05CaseReviewService.class,()->service);
      context.registerBean(Icu05OverrideService.class,()->overrides);
      context.register(Security.class,Icu05CaseReviewController.class,Icu05OverrideController.class); context.refresh();
      Icu05CaseReviewController controller=context.getBean(Icu05CaseReviewController.class);
      Requests.Icu05Exclude request=new Requests.Icu05Exclude();
      UsernamePasswordAuthenticationToken nurse=auth("ROLE_NURSE");
      assertThrows(AccessDeniedException.class,()->controller.exclude("p",request,"test-key",nurse));
      assertThrows(AccessDeniedException.class,()->controller.revoke("p","r",new Requests.Icu05Revoke(),"test-key",nurse));
      assertThrows(AccessDeniedException.class,()->controller.history("p"));
      Icu05OverrideController overrideController=context.getBean(Icu05OverrideController.class);
      assertThrows(AccessDeniedException.class,()->overrideController.create("p",new Requests.Icu05OverrideRequest(),"test-key",nurse));
      assertThrows(AccessDeniedException.class,()->overrideController.revoke("p","r",new Requests.Icu05Revoke(),"test-key",nurse));
      assertThrows(AccessDeniedException.class,()->overrideController.history("p"));
      verifyNoInteractions(service,overrides);
      for(String role:new String[]{"ROLE_DOCTOR","ROLE_QUALITY"}) {
        UsernamePasswordAuthenticationToken actor=auth(role);
        assertNotNull(controller.exclude("p",request,"test-key",actor));
        assertNotNull(overrideController.create("p",new Requests.Icu05OverrideRequest(),"test-key",actor));
      }
      verify(service,times(2)).exclude(eq("p"),eq(request),eq("test-key"),eq("reviewer"));
    }
  }
  static UsernamePasswordAuthenticationToken auth(String role) {
    UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("reviewer","",AuthorityUtils.createAuthorityList(role));
    SecurityContextHolder.getContext().setAuthentication(auth); return auth;
  }
  @Test void reviewAndAutomaticServicesCanBeConstructedWithoutCircularDependency() {
    try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()) {
      context.registerBean(Store.class,()->mock(Store.class));context.registerBean(Clock.class,Clock::systemUTC);
      context.register(Icu05Service.class,Icu05CaseReviewService.class,Icu05OverrideService.class);context.refresh();
      assertNotNull(context.getBean(Icu05Service.class));assertNotNull(context.getBean(Icu05CaseReviewService.class));
    }
  }
}
