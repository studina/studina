package com.studina.dinaapi.ai.model_armor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "model.armor")
public class ModelArmorProperties {
    private boolean enabled;

    private String projectId;

    private String location;
    
    private String templateId;

    public void setEnabled(boolean value) {
        this.enabled = value;
    }

    public void setProjectId(String value) {
        this.projectId = value;
    }

    public void setlocation(String value) {
        this.location = value;
    }

    public void setTemplateId(String value) {
        this.templateId = value;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public String getProjectId() {
        return this.projectId;
    }

    public String getLocation() {
        return this.location;
    }

	public String getTemplateId() {
		return this.templateId;
	}
}
