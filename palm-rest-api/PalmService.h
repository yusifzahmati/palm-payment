#pragma once

#include <memory>
#include <mutex>
#include <vector>
#include <string>

#include "palm/common_types.h"
#include "palm/device.h"
#include "palm/palm_arithmetic.h"
#include "palm/compare_arithmetic.h"


namespace StreamPalm {
    class ComparePalmCapture;
}

struct PalmTemplate {
    std::string fullName;
    std::string fincode;
    std::string customerNo;

    std::vector<float> irFeatures;
    std::vector<float> rgbFeatures;
    StreamPalm::StreamData irHash;
    StreamPalm::StreamData rgbHash;
    int palmType;   // 0 = left, 1 = right
};
struct RegisterResult {
    bool success = false;
    std::string message;
    int templateId = -1;
};

struct RegisterResponse {
    bool success = false;
    std::string message;
    int templateId = -1;
    int palmType = -1; // 0 left, 1 right

    // Feature vectors encoded as base64 of raw float bytes
    std::string irFeaturesB64;
    std::string rgbFeaturesB64;

    // Optional: lengths (number of floats)
    size_t irCount = 0;
    size_t rgbCount = 0;
};

struct CaptureOnceOut {
    bool success = false;
    std::string message;
    int palmType = -1;
    std::vector<float> ir;
    std::vector<float> rgb;
};


struct CandidateTpl {
    std::string finCode;
    int templateId = -1;
    std::string irB64;
    std::string rgbB64;
};

struct VerifyResponse {
    bool success = false;
    std::string message;

    int matchedTemplateId = -1;
    std::string matchedFinCode;

    float irScore = 0.0f;
    float rgbScore = 0.0f;

    float irThreshold = 0.0f;
    float rgbThreshold = 0.0f;

    int palmType = -1;
};


class PalmService {
public:
    PalmService();

    bool init();

    bool registerPalm(
    // const std::string& fullName,
    // const std::string& fincode,
    // const std::string& customerNo,
    uint32_t timeoutMs
);
    RegisterResponse PalmService::registerUserPalm(uint32_t timeoutMs);
    CaptureOnceOut PalmService::captureOnce(uint32_t timeoutMs);
    VerifyResponse PalmService::identify(uint32_t timeoutMs,
                                    const std::vector<CandidateTpl>& candidates);


private:
    std::shared_ptr<StreamPalm::Device> device_;
    std::shared_ptr<StreamPalm::PalmCapture> palm_;

    StreamPalm::Stream* stream_{nullptr};
    std::vector<PalmTemplate> registeredUsers_;
    std::shared_ptr<StreamPalm::ComparePalmCapture> comparer_;

    std::mutex mutex_;
};
