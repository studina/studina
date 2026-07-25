package com.studina.dinaapi.auth;

public record AuthResponse(String userId, String name, String email, String token) {}
