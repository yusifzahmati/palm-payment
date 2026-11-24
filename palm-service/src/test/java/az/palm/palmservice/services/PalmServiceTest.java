package az.palm.palmservice.services;

import az.palm.palmservice.domain.entities.PalmInfo;
import az.palm.palmservice.domain.entities.PalmUser;
import az.palm.palmservice.domain.repositories.PalmInfoRepository;
import az.palm.palmservice.domain.repositories.PalmUserRepository;
import az.palm.palmservice.dtos.IdentifyRequest;
import az.palm.palmservice.dtos.IdentifyResponse;
import az.palm.palmservice.dtos.PalmInfoDTO;
import az.palm.palmservice.dtos.PalmUserRegisterDTO;
import az.palm.palmservice.dtos.RegisterResponse;
import az.palm.palmservice.dtos.UserInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PalmServiceTest {

    private static final String REGISTER_URL = "http://localhost:8081/palm/register";
    private static final String IDENTIFY_URL = "http://localhost:8081/palm/identify";

    @Mock
    private PalmInfoRepository palmInfoRepository;
    @Mock
    private PalmUserRepository palmUserRepository;
    @Mock
    private PalmSDKService palmSDKService;
    @Mock
    private RestTemplate restTemplate;
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private WebClient webClient;

    private PalmService palmService;

    @BeforeEach
    void setUp() {
        palmService = new PalmService(palmInfoRepository, palmUserRepository, palmSDKService, restTemplate, webClient);
    }

    // ---------- helpers ----------

    private static PalmUserRegisterDTO registerDto(String finCode) {
        PalmUserRegisterDTO dto = new PalmUserRegisterDTO();
        dto.setFullName("Jane Doe");
        dto.setFinCode(finCode);
        dto.setCustomerNo("C-1");
        return dto;
    }

    private static RegisterResponse scannerRegisterResponse(boolean success) {
        return new RegisterResponse(success, success ? "ok" : "scan failed", 7, 1, 512, "IR_B64", 256, "RGB_B64");
    }

    private static PalmUser storedUser() {
        PalmInfo info = new PalmInfo();
        info.setId(42L);
        info.setPalmType(0);
        info.setIrFeatureSize(10);
        info.setIrFeatureBase64("IR");
        info.setRgbFeatureSize(20);
        info.setRgbFeatureBase64("RGB");

        PalmUser user = new PalmUser();
        user.setId(1L);
        user.setFullName("Jane Doe");
        user.setFinCode("1ABC234");
        user.setCustomerNo("C-1");
        user.setPalmInfo(info);
        return user;
    }

    private static IdentifyRequest identifyRequest() {
        return new IdentifyRequest(8000, List.of(new PalmInfoDTO("1ABC234", 1, "IR", "RGB")));
    }

    private void stubIdentifyCall(IdentifyResponse response) {
        when(webClient.post()
                .uri(IDENTIFY_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(any())
                .retrieve()
                .bodyToMono(IdentifyResponse.class))
                .thenReturn(response == null ? Mono.empty() : Mono.just(response));
    }

    private static IdentifyResponse identifyResponse(boolean success, String message, String finCode) {
        return new IdentifyResponse(success, message, 1, finCode, 0.9, 0.9, 0.5, 0.5, 0);
    }

    // ---------- register ----------

    @Test
    void register_rejectsNullFinCode() {
        RegisterResponse result = palmService.register(registerDto(null));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("finCode is required");
        verifyNoInteractions(palmUserRepository, palmInfoRepository, restTemplate);
    }

    @Test
    void register_rejectsBlankFinCode() {
        RegisterResponse result = palmService.register(registerDto("   "));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("finCode is required");
        verifyNoInteractions(palmUserRepository, palmInfoRepository, restTemplate);
    }

    @Test
    void register_existingUser_returnsStoredRegistrationWithoutScanning() {
        when(palmUserRepository.findByFinCode("1ABC234")).thenReturn(Optional.of(storedUser()));

        RegisterResponse result = palmService.register(registerDto("1ABC234"));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).isEqualTo("User already registered");
        assertThat(result.templateId()).isEqualTo(42);
        assertThat(result.palmType()).isZero();
        assertThat(result.irCount()).isEqualTo(10);
        assertThat(result.irFeaturesB64()).isEqualTo("IR");
        assertThat(result.rgbCount()).isEqualTo(20);
        assertThat(result.rgbFeaturesB64()).isEqualTo("RGB");
        verifyNoInteractions(restTemplate);
        verify(palmUserRepository, never()).save(any());
        verify(palmInfoRepository, never()).save(any());
    }

    @Test
    void register_newUser_persistsPalmInfoAndUserFromScannerResponse() {
        RegisterResponse scanner = scannerRegisterResponse(true);
        when(palmUserRepository.findByFinCode("1ABC234")).thenReturn(Optional.empty());
        when(restTemplate.postForObject(eq(REGISTER_URL), eq(HttpEntity.EMPTY), eq(RegisterResponse.class)))
                .thenReturn(scanner);

        RegisterResponse result = palmService.register(registerDto("1ABC234"));

        assertThat(result).isSameAs(scanner);

        ArgumentCaptor<PalmInfo> infoCaptor = ArgumentCaptor.forClass(PalmInfo.class);
        verify(palmInfoRepository).save(infoCaptor.capture());
        PalmInfo info = infoCaptor.getValue();
        assertThat(info.getPalmType()).isEqualTo(1);
        assertThat(info.getIrFeatureSize()).isEqualTo(512);
        assertThat(info.getIrFeatureBase64()).isEqualTo("IR_B64");
        assertThat(info.getRgbFeatureSize()).isEqualTo(256);
        assertThat(info.getRgbFeatureBase64()).isEqualTo("RGB_B64");

        ArgumentCaptor<PalmUser> userCaptor = ArgumentCaptor.forClass(PalmUser.class);
        verify(palmUserRepository).save(userCaptor.capture());
        PalmUser user = userCaptor.getValue();
        assertThat(user.getFullName()).isEqualTo("Jane Doe");
        assertThat(user.getFinCode()).isEqualTo("1ABC234");
        assertThat(user.getCustomerNo()).isEqualTo("C-1");
        assertThat(user.getPalmInfo()).isSameAs(info);
    }

    @Test
    void register_scannerReturnsNull_failsWithoutSaving() {
        when(palmUserRepository.findByFinCode("1ABC234")).thenReturn(Optional.empty());
        when(restTemplate.postForObject(eq(REGISTER_URL), eq(HttpEntity.EMPTY), eq(RegisterResponse.class)))
                .thenReturn(null);

        RegisterResponse result = palmService.register(registerDto("1ABC234"));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEqualTo("Palm scanner returned empty response");
        verify(palmUserRepository, never()).save(any());
        verify(palmInfoRepository, never()).save(any());
    }

    @Test
    void register_scannerReportsFailure_returnsItAndDoesNotSave() {
        RegisterResponse scanner = scannerRegisterResponse(false);
        when(palmUserRepository.findByFinCode("1ABC234")).thenReturn(Optional.empty());
        when(restTemplate.postForObject(eq(REGISTER_URL), eq(HttpEntity.EMPTY), eq(RegisterResponse.class)))
                .thenReturn(scanner);

        RegisterResponse result = palmService.register(registerDto("1ABC234"));

        assertThat(result).isSameAs(scanner);
        verify(palmUserRepository, never()).save(any());
        verify(palmInfoRepository, never()).save(any());
    }

    // ---------- getAllPalms ----------

    @Test
    void getAllPalms_mapsEveryUserToDto() {
        when(palmUserRepository.findAll()).thenReturn(List.of(storedUser()));

        List<PalmInfoDTO> result = palmService.getAllPalms();

        assertThat(result).hasSize(1);
        PalmInfoDTO dto = result.get(0);
        assertThat(dto.getFinCode()).isEqualTo("1ABC234");
        assertThat(dto.getTemplateId()).isZero(); // current mapping passes palmType as templateId
        assertThat(dto.getIrFeatureBase64()).isEqualTo("IR");
        assertThat(dto.getRgbFeatureBase64()).isEqualTo("RGB");
    }

    @Test
    void getAllPalms_noUsers_returnsEmptyList() {
        when(palmUserRepository.findAll()).thenReturn(List.of());

        assertThat(palmService.getAllPalms()).isEmpty();
    }

    // ---------- identify ----------

    @Test
    void identify_nullRequest_isNotFound() {
        assertThatThrownBy(() -> palmService.identify(null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(webClient);
    }

    @Test
    void identify_emptyCandidates_isNotFound() {
        assertThatThrownBy(() -> palmService.identify(new IdentifyRequest(8000, List.of())))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).isEqualTo("No registered palm templates found");
                });
        verifyNoInteractions(webClient);
    }

    @Test
    void identify_match_returnsUserInfo() {
        stubIdentifyCall(identifyResponse(true, "ok", "1ABC234"));
        when(palmUserRepository.findByFinCode("1ABC234")).thenReturn(Optional.of(storedUser()));

        UserInfo result = palmService.identify(identifyRequest());

        assertThat(result).isEqualTo(new UserInfo("Jane Doe", "1ABC234", "C-1"));
    }

    @Test
    void identify_emptyScannerBody_isBadGateway() {
        stubIdentifyCall(null);

        assertThatThrownBy(() -> palmService.identify(identifyRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void identify_scannerFailure_isNotFoundWithScannerMessage() {
        stubIdentifyCall(identifyResponse(false, "No match", null));

        assertThatThrownBy(() -> palmService.identify(identifyRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).isEqualTo("No match");
                });
    }

    @Test
    void identify_scannerFailureWithBlankMessage_usesDefaultMessage() {
        stubIdentifyCall(identifyResponse(false, " ", null));

        assertThatThrownBy(() -> palmService.identify(identifyRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).isEqualTo("Palm was not identified");
                });
    }

    @Test
    void identify_successWithoutFinCode_isBadGateway() {
        stubIdentifyCall(identifyResponse(true, "ok", ""));

        assertThatThrownBy(() -> palmService.identify(identifyRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getReason()).isEqualTo("Palm scanner did not return matched finCode");
                });
        verifyNoInteractions(palmUserRepository);
    }

    @Test
    void identify_matchedFinCodeNotInDatabase_isNotFound() {
        stubIdentifyCall(identifyResponse(true, "ok", "9ZZZ999"));
        when(palmUserRepository.findByFinCode("9ZZZ999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> palmService.identify(identifyRequest()))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(e.getReason()).contains("9ZZZ999");
                });
    }

    // ---------- getPalmRegisterInfo ----------

    @Test
    void getPalmRegisterInfo_delegatesToSdkService() {
        when(palmSDKService.fetchResponse(REGISTER_URL)).thenReturn("{\"raw\":true}");

        assertThat(palmService.getPalmRegisterInfo()).isEqualTo("{\"raw\":true}");
    }
}
