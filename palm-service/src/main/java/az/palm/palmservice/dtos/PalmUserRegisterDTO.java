package az.palm.palmservice.dtos;

import lombok.Data;

@Data
public class PalmUserRegisterDTO {
    private boolean registered;
    private String fullName;
    private String finCode;
    private String customerNo;

}
