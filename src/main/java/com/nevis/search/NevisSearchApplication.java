package com.nevis.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class NevisSearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(NevisSearchApplication.class, args);
    }
}

