package com.unisage.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class UnisageBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(UnisageBackendApplication.class, args);
	}

}
