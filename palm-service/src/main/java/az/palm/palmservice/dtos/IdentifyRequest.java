package az.palm.palmservice.dtos;

public record IdentifyRequest(
        long timeoutMs,
        java.util.List<PalmInfoDTO> candidates
) {}