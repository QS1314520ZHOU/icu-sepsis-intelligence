package org.icu.sepsis.config;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

/**
 * Dedicated business database (icu_sepsis_*). Marked @Primary so application writes never
 * accidentally target SmartCare / DataCenter source templates.
 *
 * <p>Credentials belong in {@code spring.data.mongodb.uri} when needed.
 */
@Configuration
public class BusinessMongoConfig {

  @Bean(destroyMethod = "close")
  @Primary
  public MongoClient businessMongoClient(@Value("${spring.data.mongodb.uri}") String uri) {
    return MongoClients.create(uri);
  }

  @Bean
  @Primary
  public MongoTemplate mongoTemplate(
      @Qualifier("businessMongoClient") MongoClient client,
      @Value("${spring.data.mongodb.uri}") String uri) {
    String db = new com.mongodb.ConnectionString(uri).getDatabase();
    if (db == null || db.isBlank())
      throw new IllegalStateException("spring.data.mongodb.uri must include a database name");
    return new MongoTemplate(new SimpleMongoClientDatabaseFactory(client, db));
  }
}
