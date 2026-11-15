package az.palm.palmservice.dtos;

public record IdentifyResponse(
        boolean success,
        String message,
        int matchedTemplateId,
        String matchedFinCode,
        double irScore,
        double rgbScore,
        double irThreshold,
        double rgbThreshold,
        int palmType
) {}