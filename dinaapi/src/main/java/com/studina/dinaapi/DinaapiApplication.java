package com.studina.dinaapi;

import java.util.HashMap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.bind.annotation.GetMapping;

@SpringBootApplication
@RestController
public class DinaapiApplication {

	public static void main(String[] args) {
		SpringApplication.run(DinaapiApplication.class, args);
	}

	@GetMapping(path="hello", produces="application/json")
	public ResponseEntity<HashMap<String, String>> getHello() {
		HashMap<String, String> map = new HashMap<>();
		map.put("data", "Hi from dina!!!! :)");
		return ResponseEntity.ok().body(map);
	}
}
