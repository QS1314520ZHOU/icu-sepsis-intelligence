package org.icu.sepsis;

import java.time.Clock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

@SpringBootApplication
public class SepsisApplication {
  public static void main(String[] args) {
    SpringApplication.run(SepsisApplication.class, args);
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  MongoTransactionManager transactionManager(MongoDatabaseFactory f) {
    return new MongoTransactionManager(f);
  }
}
