package com.studina.dinaapi.ai.settings;

import java.util.Optional;

public interface UserLlmSettingsRepository {

    Optional<StoredUserLlmSettings> findByUserId(String userId);

    StoredUserLlmSettings save(StoredUserLlmSettings settings);

    void deleteByUserId(String userId);
}
