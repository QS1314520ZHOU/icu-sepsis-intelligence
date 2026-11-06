package org.icu.sepsis.integration;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
import java.time.*;import org.junit.jupiter.api.Test;import org.icu.sepsis.application.Store;import org.icu.sepsis.domain.Domain.Patient;import org.springframework.context.ApplicationEventPublisher;
class SourceChangeDispatcherTest {
 final Instant now=Instant.parse("2026-11-06T01:00:00Z");final Store store=mock(Store.class);final ApplicationEventPublisher publisher=mock(ApplicationEventPublisher.class);final SourceChangeDispatcher dispatcher=new SourceChangeDispatcher(store,publisher,Clock.fixed(now,ZoneOffset.UTC));
 SourceChangeDispatcherTest(){var patient=new Patient();patient.id="p";patient.encounterId="e";patient.department="ICU";when(store.patient("p")).thenReturn(patient);}
 SourceChangeDispatcher.Change change(String id,Instant time){return new SourceChangeDispatcher.Change("p","e","ICU","laboratory",id,"synthetic-source",time,now);}
 @Test void duplicateStillAuthorizesButCannotInvalidateTwice(){var event=change("a",now);assertEquals("invalidated",dispatcher.dispatch(event));assertEquals("duplicate",dispatcher.dispatch(event));verify(store,times(2)).patient("p");verify(publisher,times(1)).publishEvent(event);}
 @Test void lateEventsInvalidateRatherThanOverwritingWatermark(){assertEquals("invalidated",dispatcher.dispatch(change("new",now)));assertEquals("invalidated",dispatcher.dispatch(change("late",now.minusSeconds(3600))));}
 @Test void crossEncounterAndFutureOrConflictingEventsFailClosed(){assertThrows(IllegalArgumentException.class,()->dispatcher.dispatch(new SourceChangeDispatcher.Change("p","other","ICU","laboratory","a","synthetic-source",now,now)));assertThrows(IllegalArgumentException.class,()->dispatcher.dispatch(change("a",now.plusSeconds(1))));verifyNoInteractions(publisher);dispatcher.dispatch(change("a",now));assertThrows(IllegalArgumentException.class,()->dispatcher.dispatch(change("a",now.minusSeconds(1))));}
 @Test void publicationFailureAllowsCompensationRetry(){var event=change("a",now);doThrow(new IllegalStateException("synthetic failure")).doNothing().when(publisher).publishEvent(event);assertThrows(IllegalStateException.class,()->dispatcher.dispatch(event));assertEquals("invalidated",dispatcher.dispatch(event));}
 @Test void separateSourcesMayUseTheSameEventId(){dispatcher.dispatch(change("a",now));assertEquals("invalidated",dispatcher.dispatch(new SourceChangeDispatcher.Change("p","e","ICU","laboratory","a","another-source",now,now)));}
}
