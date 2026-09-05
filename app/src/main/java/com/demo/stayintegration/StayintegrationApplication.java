package com.demo.stayintegration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class StayintegrationApplication {

	public static void main(String[] args) {
		SpringApplication.run(StayintegrationApplication.class, args);
	}

}
