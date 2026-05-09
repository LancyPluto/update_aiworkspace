package com.aiminilab.aitoolmarket;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AiToolMarketApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiToolMarketApplication.class, args);
    }
}
