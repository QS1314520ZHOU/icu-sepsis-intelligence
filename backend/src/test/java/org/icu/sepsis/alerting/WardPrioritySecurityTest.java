package org.icu.sepsis.alerting;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
class WardPrioritySecurityTest {
  @Configuration @EnableGlobalMethodSecurity(prePostEnabled=true) static class Security {}
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  void role(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("reviewer","",AuthorityUtils.createAuthorityList(role)));}
  @Test void queueReadAndFeedbackStatisticsHaveDistinctRolePermissions(){try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext()){
    WardPriorityService queue=mock(WardPriorityService.class);AlertReviewStatsService stats=mock(AlertReviewStatsService.class);
    context.registerBean(WardPriorityService.class,()->queue);context.registerBean(AlertReviewStatsService.class,()->stats);
    context.register(Security.class,WardPriorityController.class);context.refresh();WardPriorityController controller=context.getBean(WardPriorityController.class);
    role("ROLE_VIEWER");assertThrows(AccessDeniedException.class,controller::priority);assertThrows(AccessDeniedException.class,()->controller.reviews(7));verifyNoInteractions(queue,stats);
    role("ROLE_NURSE");assertNotNull(controller.priority());assertThrows(AccessDeniedException.class,()->controller.reviews(7));verifyNoInteractions(stats);
    for(String role:new String[]{"ROLE_DOCTOR","ROLE_QUALITY"}){role(role);assertNotNull(controller.priority());assertNotNull(controller.reviews(7));}
    verify(queue,times(3)).snapshot();verify(stats,times(2)).report(7);
  }}
}
