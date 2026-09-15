package com.cagritasoz.user_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Required for OutboxRelay's @Scheduled method to actually run - without this, @Scheduled is
// silently ignored (no error, the method just never fires). Same requirement as
// ingestion-service's ContinuousDataSimulator and usage-service's DeviceIdCache.
@EnableScheduling
@SpringBootApplication
public class UserServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(UserServiceApplication.class, args);
	}

}
