package az.palm.palmservice.dtos;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterResponse(
        boolean success,
        String message,
        int templateId,
        int palmType,
        int irCount,
        String irFeaturesB64,
        int rgbCount,
        String rgbFeaturesB64
) {}