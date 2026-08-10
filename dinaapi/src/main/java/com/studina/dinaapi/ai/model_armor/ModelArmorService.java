package com.studina.dinaapi.ai.model_armor;

import com.google.cloud.modelarmor.v1.DataItem;
import com.google.cloud.modelarmor.v1.FilterMatchState;
import com.google.cloud.modelarmor.v1.InvocationResult;
import com.google.cloud.modelarmor.v1.ModelArmorClient;
import com.google.cloud.modelarmor.v1.ModelArmorSettings;
import com.google.cloud.modelarmor.v1.SanitizationResult;
import com.google.cloud.modelarmor.v1.SanitizeModelResponseRequest;
import com.google.cloud.modelarmor.v1.SanitizeModelResponseResponse;
import com.google.cloud.modelarmor.v1.SanitizeUserPromptRequest;
import com.google.cloud.modelarmor.v1.SanitizeUserPromptResponse;
import com.google.cloud.modelarmor.v1.TemplateName;
import java.io.IOException;

import org.springframework.stereotype.Service;

@Service
public class ModelArmorService {
    private ModelArmorProperties modelArmorProperties;

    public ModelArmorService(ModelArmorProperties modelArmorProperties) {
        this.modelArmorProperties = modelArmorProperties;
    }

    public SanitizeUserPromptResponse sanitizeUserPrompt(String userPrompt) throws IOException {
        // Incorporar chequeo
        if(!modelArmorProperties.isEnabled()) {
            return SanitizeUserPromptResponse.newBuilder().setSanitizationResult(SanitizationResult.getDefaultInstance()).build();
        }

        String locationId = this.modelArmorProperties.getLocation();
        // Endpoint to call the Model Armor server.
        String apiEndpoint = String.format("modelarmor.%s.rep.googleapis.com:443", locationId);
        ModelArmorSettings modelArmorSettings = ModelArmorSettings.newBuilder()
            .setEndpoint(apiEndpoint)
            .build();

        try (ModelArmorClient client = ModelArmorClient.create(modelArmorSettings)) {
            // Build the resource name of the template.
            
            String templateName = TemplateName.of(
                this.modelArmorProperties.getProjectId(),
                this.modelArmorProperties.getLocation(),
                this.modelArmorProperties.getTemplateId()
            ).toString();

            // Prepare the request.
            SanitizeUserPromptRequest request = SanitizeUserPromptRequest.newBuilder()
                .setName(templateName)
                .setUserPromptData(DataItem.newBuilder().setText(userPrompt).build())
                .build();

            SanitizeUserPromptResponse response = client.sanitizeUserPrompt(request);

            return response;
        }
    }

    public SanitizeModelResponseResponse sanitizeModelResponse(String modelResponse) throws IOException {

        // Endpoint to call the Model Armor server.
        String apiEndpoint = String.format("modelarmor.%s.rep.googleapis.com:443", this.modelArmorProperties.getLocation());
        ModelArmorSettings modelArmorSettings = ModelArmorSettings.newBuilder().setEndpoint(apiEndpoint)
            .build();

        try (ModelArmorClient client = ModelArmorClient.create(modelArmorSettings)) {
            // Build the resource name of the template.
            String name = TemplateName.of(this.modelArmorProperties.getProjectId(), this.modelArmorProperties.getLocation(), this.modelArmorProperties.getTemplateId()).toString();

            // Prepare the request.
            SanitizeModelResponseRequest request = 
                SanitizeModelResponseRequest.newBuilder()
                    .setName(name)
                    .setModelResponseData(
                    DataItem.newBuilder().setText(modelResponse)
                    .build())
                    .build();

            SanitizeModelResponseResponse response = client.sanitizeModelResponse(request);

            return response;
        }
    }

    public boolean isPromptSafe(SanitizeUserPromptResponse response) {
        if (response == null || !response.hasSanitizationResult()) {
            return false;
        }

        var result = response.getSanitizationResult();

        return result.getFilterMatchState() == FilterMatchState.NO_MATCH_FOUND
            && result.getInvocationResult() == InvocationResult.SUCCESS;
    }
}
