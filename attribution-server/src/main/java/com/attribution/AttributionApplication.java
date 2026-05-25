package com.attribution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AttributionApplication {
    public static void main(String[] args) {
        SpringApplication.run(AttributionApplication.class, args);
    }
}
