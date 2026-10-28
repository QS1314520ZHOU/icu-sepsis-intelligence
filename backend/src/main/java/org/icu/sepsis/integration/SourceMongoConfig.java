package org.icu.sepsis.integration;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.util.concurrent.TimeUnit;
import org.bson.UuidRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.util.StringUtils;

/**
 * Independent read-only clients for hospital SmartCare and DataCenter.
 *
 * <p>Do NOT inject these by type alone: {@code businessMongoClient} is {@code @Primary}.
 * Always use {@code @Qualifier("smartcareMongoTemplate")} / {@code @Qualifier("dataCenterMongoTemplate")}.
 *
 * <p>DataCenter never reuses the SmartCare client or credentials. Each side has its own
 * URI / username / password / authSource. Empty username = no-auth.
 */
@Configuration
public class SourceMongoConfig {
  private static final Logger log = LoggerFactory.getLogger(SourceMongoConfig.class);

  // ---------- SmartCare ----------

  @Bean(destroyMethod = "close", name = "smartcareMongoClient")
  @ConditionalOnProperty(name = "app.source.mode", havingValue = "smartcare")
  public MongoClient smartcareMongoClient(
      @Value("${app.source.smartcare.uri}") String uri,
      @Value("${app.source.smartcare.username:}") String username,
      @Value("${app.source.smartcare.password:}") String password,
      @Value("${app.source.smartcare.auth-source:}") String authSource,
      @Value("${app.source.connect-timeout-ms:5000}") int connectTimeoutMs,
      @Value("${app.source.socket-timeout-ms:15000}") int socketTimeoutMs) {
    return createClient("SmartCare", uri, username, password, authSource, connectTimeoutMs, socketTimeoutMs);
  }

  @Bean(name = "smartcareMongoTemplate")
  @ConditionalOnProperty(name = "app.source.mode", havingValue = "smartcare")
  public MongoTemplate smartcareMongoTemplate(
      @Qualifier("smartcareMongoClient") MongoClient smartcareMongoClient,
      @Value("${app.source.smartcare.uri}") String uri) {
    return templateFor("SmartCare", smartcareMongoClient, uri);
  }

  // ---------- DataCenter (独立 client，禁止复用 SmartCare / @Primary) ----------

  @Bean(destroyMethod = "close", name = "dataCenterMongoClient")
  @ConditionalOnProperty(name = "app.source.mode", havingValue = "smartcare")
  public MongoClient dataCenterMongoClient(
      @Value("${app.source.datacenter.uri}") String uri,
      @Value("${app.source.datacenter.username:}") String username,
      @Value("${app.source.datacenter.password:}") String password,
      @Value("${app.source.datacenter.auth-source:}") String authSource,
      @Value("${app.source.connect-timeout-ms:5000}") int connectTimeoutMs,
      @Value("${app.source.socket-timeout-ms:15000}") int socketTimeoutMs) {
    return createClient("DataCenter", uri, username, password, authSource, connectTimeoutMs, socketTimeoutMs);
  }

  @Bean(name = "dataCenterMongoTemplate")
  @ConditionalOnProperty(name = "app.source.mode", havingValue = "smartcare")
  public MongoTemplate dataCenterMongoTemplate(
      @Qualifier("dataCenterMongoClient") MongoClient dataCenterMongoClient,
      @Value("${app.source.datacenter.uri}") String uri) {
    return templateFor("DataCenter", dataCenterMongoClient, uri);
  }

  /** Fail fast if DataCenter template accidentally bound to another database. */
  @Bean
  @ConditionalOnProperty(name = "app.source.mode", havingValue = "smartcare")
  public Object dataCenterMongoSanityCheck(
      @Qualifier("dataCenterMongoTemplate") MongoTemplate dataCenterMongoTemplate,
      @Qualifier("smartcareMongoTemplate") MongoTemplate smartcareMongoTemplate,
      @Qualifier("dataCenterMongoClient") MongoClient dataCenterMongoClient,
      @Qualifier("smartcareMongoClient") MongoClient smartcareMongoClient) {
    String dcDb = dataCenterMongoTemplate.getDb().getName();
    String scDb = smartcareMongoTemplate.getDb().getName();
    if (!"DataCenter".equalsIgnoreCase(dcDb) && !dcDb.toLowerCase().startsWith("datacenter"))
      throw new IllegalStateException(
          "dataCenterMongoTemplate is bound to unexpected database: " + dcDb);
    if (dataCenterMongoClient == smartcareMongoClient)
      throw new IllegalStateException("DataCenter MongoClient must not be the SmartCare client");
    dataCenterMongoTemplate.getDb().runCommand(new org.bson.Document("ping", 1));
    log.info("Mongo sources OK: SmartCare db={}, DataCenter db={}", scDb, dcDb);
    return Boolean.TRUE;
  }

  private static MongoClient createClient(
      String label,
      String uri,
      String username,
      String password,
      String authSource,
      int connectTimeoutMs,
      int socketTimeoutMs) {
    ConnectionString cs = new ConnectionString(uri);
    String db = cs.getDatabase();
    if (db == null || db.isBlank())
      throw new IllegalStateException(label + " Mongo URI must include a database name: " + uri);

    MongoClientSettings.Builder builder =
        MongoClientSettings.builder()
            .applyConnectionString(cs)
            .applyToClusterSettings(
                s -> s.serverSelectionTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS))
            .applyToSocketSettings(s -> s.connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS))
            .applicationName("icu-sepsis-" + label.toLowerCase())
            .uuidRepresentation(UuidRepresentation.JAVA_LEGACY);

    // Credentials only for THIS client. Never inherit from the other source or @Primary.
    if (StringUtils.hasText(username)) {
      String authDb = StringUtils.hasText(authSource) ? authSource : db;
      builder.credential(
          MongoCredential.createCredential(username.trim(), authDb, (password == null ? "" : password).toCharArray()));
      log.info("{} client auth: user={} authSource={}", label, username, authDb);
    } else {
      log.info("{} client: no-auth (empty username)", label);
    }

    if (cs.getSocketTimeout() == null) {
      builder.applyToSocketSettings(s -> s.readTimeout(socketTimeoutMs, TimeUnit.MILLISECONDS));
    }
    return MongoClients.create(builder.build());
  }

  private static MongoTemplate templateFor(String label, MongoClient client, String uri) {
    String path = new ConnectionString(uri).getDatabase();
    if (path == null || path.isBlank())
      throw new IllegalStateException(label + " Mongo URI must include a database name: " + uri);
    return new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, path));
  }
}
