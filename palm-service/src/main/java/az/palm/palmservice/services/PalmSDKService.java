package az.palm.palmservice.services;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class PalmSDKService {

    private final WebClient webClient;

    public PalmSDKService(WebClient webClient) {
        this.webClient = webClient;
    }

    public String fetchResponse(String url) {
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(); // blocks current thread (fine for simple non-reactive apps)
    }
}
