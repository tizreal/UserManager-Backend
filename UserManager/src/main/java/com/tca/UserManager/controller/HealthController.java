package com.tca.UserManager.controller;


import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class HealthController {
	
	private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        jdbc.queryForObject("SELECT 1", Integer.class); // also wakes Neon
        return ResponseEntity.ok("ok");
    }

}
