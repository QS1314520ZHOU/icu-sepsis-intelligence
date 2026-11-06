package org.icu.sepsis.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCursor;
import java.util.*;
import org.bson.Document;
import org.icu.sepsis.sepsis.Icu05Rules.Evidence;
import org.junit.jupiter.api.Test;

class Icu05SourceCompletenessTest {
  @SuppressWarnings("unchecked")
  private FindIterable<Document> query(int size, MongoCursor<Document> cursor) {
    FindIterable<Document> query = mock(FindIterable.class);
    Iterator<Document> values = java.util.stream.IntStream.range(0,size)
        .mapToObj(i->new Document("value",i)).iterator();
    when(query.limit(3)).thenReturn(query); when(query.iterator()).thenReturn(cursor);
    when(cursor.hasNext()).thenAnswer(inv->values.hasNext());
    when(cursor.next()).thenAnswer(inv->values.next()); return query;
  }
  @Test @SuppressWarnings("unchecked") void exactLimitIsCompleteAndCursorClosed() {
    Evidence e = new Evidence(); MongoCursor<Document> cursor = mock(MongoCursor.class);
    assertEquals(2,Icu05SourceReader.bounded(query(2,cursor),2,e,"bedside").size());
    assertTrue(e.failed.isEmpty()); assertTrue(e.truncatedSources.isEmpty()); verify(cursor).close();
  }
  @Test @SuppressWarnings("unchecked") void extraRecordMarksSourceIncompleteAndClosesCursor() {
    Evidence e = new Evidence(); MongoCursor<Document> cursor = mock(MongoCursor.class);
    assertEquals(2,Icu05SourceReader.bounded(query(3,cursor),2,e,"bedside").size());
    assertEquals(Set.of("bedside"),e.failed); assertEquals(Set.of("bedside"),e.truncatedSources); verify(cursor).close();
  }
  @Test @SuppressWarnings("unchecked") void cursorIsClosedWhenSourceThrows() {
    Evidence e = new Evidence(); MongoCursor<Document> cursor = mock(MongoCursor.class);
    FindIterable<Document> query = query(1,cursor);
    when(cursor.hasNext()).thenThrow(new IllegalStateException("synthetic read failure"));
    assertThrows(IllegalStateException.class,()->Icu05SourceReader.bounded(query,2,e,"bedside")); verify(cursor).close();
  }

  @Test void lactateUnitMustBeExplicitAndSupported() {
    assertTrue(Icu05SourceReader.confirmedLactateUnit(" mmol/L "));
    for(String unit:List.of("", "mg/dL", "mmol", "unknown")) assertFalse(Icu05SourceReader.confirmedLactateUnit(unit));
    assertFalse(Icu05SourceReader.confirmedLactateUnit(null));
  }
}
