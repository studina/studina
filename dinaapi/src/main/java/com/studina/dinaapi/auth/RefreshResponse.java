package com.studina.dinaapi.auth;

public record RefreshResponse(String token, String refreshToken, long expiresIn) {}
