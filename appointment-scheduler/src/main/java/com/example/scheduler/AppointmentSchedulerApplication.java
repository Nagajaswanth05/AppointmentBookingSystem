package com.example.scheduler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the REST API: {@code mvn spring-boot:run}. */
@SpringBootApplication
public class AppointmentSchedulerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AppointmentSchedulerApplication.class, args);
    }
}
