package az.palm.palmservice.services;

import az.palm.palmservice.domain.entities.PalmInfo;
import az.palm.palmservice.domain.entities.PalmUser;
import az.palm.palmservice.domain.repositories.PalmInfoRepository;
import az.palm.palmservice.domain.repositories.PalmUserRepository;
import az.palm.palmservice.dtos.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PalmService {

    private final PalmInfoRepository palmInfoRepository;
    private final PalmUserRepository palmUserRepository;
    private final PalmSDKService palmSDKService;
    private final RestTemplate restTemplate;

    private final WebClient webClient = WebClient.builder().build();




    public String getPalmRegisterInfo(){
        String url = "http://localhost:8081/palm/register";

        return palmSDKService.fetchResponse(url);
    }

    @Transactional
    public synchronized RegisterResponse register(PalmUserRegisterDTO user) {
        if (user.getFinCode() == null || user.getFinCode().isBlank()) {
            return new RegisterResponse(false, "finCode is required", 0, 0, 0, null, 0, null);
        }

        PalmUser existingUser = palmUserRepository.findByFinCode(user.getFinCode()).orElse(null);
        if (existingUser != null) {
            log.info("Registration skipped. User with finCode {} already exists", user.getFinCode());
            return toRegisterResponse(existingUser, "User already registered");
        }

        String url = "http://localhost:8081/palm/register";
        RegisterResponse response = restTemplate.postForObject(url, HttpEntity.EMPTY, RegisterResponse.class);
        if (response == null) {
            return new RegisterResponse(false, "Palm scanner returned empty response", 0, 0, 0, null, 0, null);
        }

        log.info("Register Response :{}",response.message());
        if (!response.success()) {
            return response;
        }

        PalmUser palmUser = new PalmUser();
        palmUser.setCustomerNo(user.getCustomerNo());
        palmUser.setFullName(user.getFullName());
        palmUser.setFinCode(user.getFinCode());
        PalmInfo palmInfo = new PalmInfo();
        palmInfo.setPalmType(response.palmType());
        palmInfo.setIrFeatureSize(response.irCount());
        palmInfo.setIrFeatureBase64(response.irFeaturesB64());
        palmInfo.setRgbFeatureSize(response.rgbCount());
        palmInfo.setRgbFeatureBase64(response.rgbFeaturesB64());
        palmInfoRepository.save(palmInfo);
        palmUser.setPalmInfo(palmInfo);
        palmUserRepository.save(palmUser);

        return response;
    }

    private RegisterResponse toRegisterResponse(PalmUser palmUser, String message) {
        PalmInfo palmInfo = palmUser.getPalmInfo();
        return new RegisterResponse(
                true,
                message,
                palmInfo.getId() == null ? 0 : Math.toIntExact(palmInfo.getId()),
                palmInfo.getPalmType(),
                palmInfo.getIrFeatureSize(),
                palmInfo.getIrFeatureBase64(),
                palmInfo.getRgbFeatureSize(),
                palmInfo.getRgbFeatureBase64()
        );
    }


    public List<PalmInfoDTO> getAllPalms(){
        List<PalmUser> palms = palmUserRepository.findAll();

        return palms.stream().map(p -> new PalmInfoDTO(p.getFinCode(),p.getPalmInfo().getPalmType(),
                p.getPalmInfo().getIrFeatureBase64(),
                p.getPalmInfo().getRgbFeatureBase64())).collect(Collectors.toList());
    }



    public UserInfo identify(IdentifyRequest req) {
        if (req == null || req.candidates() == null || req.candidates().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No registered palm templates found");
        }

        IdentifyResponse response =  webClient.post()
                .uri("http://localhost:8081/palm/identify")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .retrieve()
                .bodyToMono(IdentifyResponse.class)
                .block();

        if (response == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Palm scanner returned empty identify response");
        }

        if (!response.success()) {
            String message = response.message() == null || response.message().isBlank()
                    ? "Palm was not identified"
                    : response.message();
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }

        if (response.matchedFinCode() == null || response.matchedFinCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Palm scanner did not return matched finCode");
        }

        PalmUser user = palmUserRepository.findByFinCode(response.matchedFinCode())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Matched finCode is not registered: " + response.matchedFinCode()));

        log.info("User found {}",user);
        return new UserInfo(user.getFullName(),user.getFinCode(),user.getCustomerNo());
    }




}
