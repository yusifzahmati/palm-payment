package az.palm.palmservice.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PalmInfoDTO {
    private String finCode;
    private int templateId;
    private String irFeatureBase64;;
    private String rgbFeatureBase64;


}
