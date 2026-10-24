package com.studina.dinaapi.ai.settings;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.studina.dinaapi.auth.DirectusAuthorizationService;

@RestController
@RequestMapping("/ai/settings")
public class UserLlmSettingsController {

    private final DirectusAuthorizationService authorizationService;
    private final UserLlmSettingsService settingsService;

    public UserLlmSettingsController(
            DirectusAuthorizationService authorizationService,
            UserLlmSettingsService settingsService
    ) {
        this.authorizationService = authorizationService;
        this.settingsService = settingsService;
    }

    @GetMapping
    public UserLlmSettingsView current(
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorizationHeader
    ) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        return settingsService.current(userId);
    }

    @PutMapping
    public UserLlmSettingsView update(
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorizationHeader,
            @RequestBody UpdateUserLlmSettingsRequest request
    ) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        return settingsService.update(userId, request);
    }

    @DeleteMapping
    public UserLlmSettingsView reset(
            @RequestHeader(
                    value = HttpHeaders.AUTHORIZATION,
                    required = false
            ) String authorizationHeader
    ) {
        String userId = authorizationService.requireUserId(authorizationHeader);
        return settingsService.reset(userId);
    }
}
