package az.palm.palmservice.controllers;


import az.palm.palmservice.domain.entities.PalmUser;
import az.palm.palmservice.dtos.*;
import az.palm.palmservice.services.PalmService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/palm")
@RequiredArgsConstructor
public class PalmRegisterController {

    private final PalmService palmService;


    @PostMapping("/register")
    public RegisterResponse registerPalm(@RequestBody PalmUserRegisterDTO register){
        return palmService.register(register);
    }


    @GetMapping
    public List<PalmInfoDTO> getAllPalms(){
        return palmService.getAllPalms();
    }

    @PostMapping("/identify")
    public ResponseEntity<UserInfo> authPalm(@RequestParam(defaultValue = "8000") long timeoutMs) {
        List<PalmInfoDTO> candidates = palmService.getAllPalms();
        IdentifyRequest req = new IdentifyRequest(timeoutMs, candidates);
        return ResponseEntity.ok(palmService.identify(req));
    }
}
