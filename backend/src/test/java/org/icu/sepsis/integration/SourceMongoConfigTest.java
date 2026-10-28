package org.icu.sepsis.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.mongodb.ConnectionString;
import org.junit.jupiter.api.Test;

class SourceMongoConfigTest {
  @Test
  void sourceUrisPointAtDistinctDatabases() {
    assertEquals(
        "SmartCare",
        new ConnectionString("mongodb://192.168.31.185:27017/SmartCare").getDatabase());
    assertEquals(
        "DataCenter",
        new ConnectionString("mongodb://192.168.31.185:27017/DataCenter").getDatabase());
  }

  @Test
  void independentAuthSourcesDoNotCross() {
    ConnectionString sc =
        new ConnectionString("mongodb://u:sc@192.168.31.185:27017/SmartCare?authSource=SmartCare");
    ConnectionString dc =
        new ConnectionString("mongodb://u:dc@192.168.31.185:27017/DataCenter?authSource=DataCenter");
    assertNotNull(sc.getCredential());
    assertNotNull(dc.getCredential());
    assertEquals("SmartCare", sc.getCredential().getSource());
    assertEquals("DataCenter", dc.getCredential().getSource());
    assertNotEquals(sc.getCredential().getSource(), dc.getCredential().getSource());
  }
}
