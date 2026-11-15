package az.palm.palmservice.dtos;

import lombok.Data;

@Data
public class PalmRegisterDTO {
    private Long palmType;
    private Long irFeatureSize;
    private Long rgbFeatureSize;
    private String irFeatureBase64;
    private String rgbFeatureBase64;
}
