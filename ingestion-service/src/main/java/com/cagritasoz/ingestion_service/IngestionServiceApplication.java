package com.cagritasoz.ingestion_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Required for ContinuousDataSimulator's @Scheduled method to actually run - without this,
// @Scheduled is silently ignored (no error, the method just never fires).
@EnableScheduling
@SpringBootApplication
public class IngestionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(IngestionServiceApplication.class, args);
	}

}
