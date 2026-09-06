package com.studina.dinaapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class DinaapiApplication {

	public static void main(String[] args) {
		SpringApplication.run(DinaapiApplication.class, args);
	}
}
