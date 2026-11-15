#include "PalmService.h"

#include <thread>
#include <atomic>
#include <vector>
#include <string>
#include <cstdint>
#include <cstring>
#include "palm/device.h"
#include "palm/arithmetic_device.h"
#include "palm/compare_arithmetic.h"
#include "palm/stream_types.h"
#include <condition_variable>

using namespace StreamPalm;

PalmService::PalmService() {}


static const char* BASE64_CHARS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "abcdefghijklmnopqrstuvwxyz"
    "0123456789+/";

bool PalmService::init() {
    std::vector<DeviceInformation> devices;
    auto manager = DeviceManager::GetInstance();

    if (!manager || manager->GetDeviceList(devices) != 0 || devices.empty()) {
        return false;
    }

    device_ = manager->CreateDevice(devices[0]);
    if (!device_) return false;

    if (device_->Open() != 0) return false;

    // Create & start stream
    if (device_->CreateStream(stream_, StreamPalm::StreamType::kRgbIr) != 0 ||
        stream_ == nullptr) {
        return false;
        }

    if (stream_->Start() != 0) {
        return false;
    }

    auto arithmeticDevice =
        std::dynamic_pointer_cast<StreamPalm::ArithmeticDevice>(device_);
    if (!arithmeticDevice) return false;

    arithmeticDevice->SetLedMode(StreamPalm::LED_STANDBY_MODE);

    arithmeticDevice->EnableDimPalm(true);

    if (PalmCapture::Create(device_, &palm_) != 0 || !palm_) {
        return false;
    }

    comparer_ = std::dynamic_pointer_cast<StreamPalm::ComparePalmCapture>(palm_);
    if (!comparer_) {
        // In some SDK builds, compare might not be supported by this PalmCapture instance
        return false;
    }

    palm_->EnableBase64Encode(false);
    return true;
}


bool PalmService::registerPalm(uint32_t timeoutMs) {
    std::lock_guard<std::mutex> lock(mutex_);

    CapturePalmResult captureResult;
    std::atomic<bool> done{false};

    int ret = palm_->CapturePalmOnce(
        [&](const CapturePalmResult& r) {
            captureResult = r;
            done.store(true);
        },
        timeoutMs
    );

    while (!done.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    if (ret != 0 ||
        captureResult.palm_detect_result != kDimPalmSuccess) {
        return false;
        }

    // Convert IR features
    float* irPtr =
        reinterpret_cast<float*>(captureResult.ir_features.data.get());
    size_t irCount =
        captureResult.ir_features.data_len / sizeof(float);

    // Convert RGB features
    float* rgbPtr =
        reinterpret_cast<float*>(captureResult.rgb_features.data.get());
    size_t rgbCount =
        captureResult.rgb_features.data_len / sizeof(float);

    PalmTemplate tpl;
    //tpl.userId = "user1"; // TODO: get from HTTP request body
    tpl.palmType = captureResult.palm_type;
    tpl.irFeatures.assign(irPtr, irPtr + irCount);
    tpl.rgbFeatures.assign(rgbPtr, rgbPtr + rgbCount);

    registeredUsers_.push_back(std::move(tpl));
    return true;
}

static std::string base64Encode(const uint8_t* data, size_t len) {
    std::string out;
    out.reserve(((len + 2) / 3) * 4);

    for (size_t i = 0; i < len; i += 3) {
        uint32_t triple = 0;
        int padding = 0;

        triple |= data[i] << 16;

        if (i + 1 < len) {
            triple |= data[i + 1] << 8;
        } else {
            padding++;
        }

        if (i + 2 < len) {
            triple |= data[i + 2];
        } else {
            padding++;
        }

        out.push_back(BASE64_CHARS[(triple >> 18) & 0x3F]);
        out.push_back(BASE64_CHARS[(triple >> 12) & 0x3F]);
        out.push_back(padding >= 2 ? '=' : BASE64_CHARS[(triple >> 6) & 0x3F]);
        out.push_back(padding >= 1 ? '=' : BASE64_CHARS[triple & 0x3F]);
    }

    return out;
}

static std::string base64FromStreamData(const StreamPalm::StreamData& s) {
    if (!s.data || s.data_len == 0) return "";
    return base64Encode(reinterpret_cast<const uint8_t*>(s.data.get()), s.data_len);
}


RegisterResponse PalmService::registerUserPalm(uint32_t timeoutMs) {
    std::lock_guard<std::mutex> apiLock(mutex_);

    RegisterResponse out;

    std::mutex m;
    std::condition_variable cv;

    std::atomic<bool> done{false};
    bool gotGood = false;

    StreamPalm::CapturePalmResult good{};
    StreamPalm::CapturePalmResult lastTerminal{};

    auto cb = [&](const StreamPalm::CapturePalmResult& r) {
        // Ignore any callbacks after we're done (prevents overwrite)
        if (done.load(std::memory_order_acquire)) return;

        const bool hasFeatures =
            (r.ir_features.data && r.ir_features.data_len > 0) &&
            (r.rgb_features.data && r.rgb_features.data_len > 0);

        // Vendor-sample-style "success" condition
        const bool isGood =
            (r.capture_handler_status == StreamPalm::PalmDetectStatus::kPalmDetected) &&
            (r.live_palm_errors == 0) &&
            hasFeatures;

        const bool isTerminal =
            (r.capture_handler_status == StreamPalm::PalmDetectStatus::kTimeout) ||
            (r.capture_handler_status == StreamPalm::PalmDetectStatus::kManualStop);

        if (isGood) {
            {
                std::lock_guard<std::mutex> lk(m);
                good = r;
                gotGood = true;
            }
            done.store(true, std::memory_order_release);
            cv.notify_one();
            return;
        }

        // Only record terminal if we never got a good frame
        if (isTerminal) {
            {
                std::lock_guard<std::mutex> lk(m);
                lastTerminal = r;
            }
            done.store(true, std::memory_order_release);
            cv.notify_one();
            return;
        }
    };

    int ret = palm_->CapturePalmOnce(cb, timeoutMs);
    if (ret != 0) {
        out.success = false;
        out.message = "CapturePalmOnce start failed ret=" + std::to_string(ret);
        return out;
    }

    {
        std::unique_lock<std::mutex> lk(m);
        cv.wait_for(lk, std::chrono::milliseconds(timeoutMs + 500),
                    [&] { return done.load(std::memory_order_acquire); });
    }

    // Stop capture to free internal threads (safe now: callback ignores after done=true)
    palm_->StopPalmCapture();

    if (!done.load()) {
        out.success = false;
        out.message = "Timeout waiting for capture callback";
        return out;
    }

    if (!gotGood) {
        // terminal ended without a good capture
        if (lastTerminal.capture_handler_status == StreamPalm::PalmDetectStatus::kTimeout) {
            out.success = false;
            out.message = "Capture timeout";
        } else {
            out.success = false;
            out.message = "Stopped before good palm captured";
        }
        return out;
    }

    // Use the latched good result
    const auto& r = good;

    const size_t irCount  = r.ir_features.data_len / sizeof(float);
    const size_t rgbCount = r.rgb_features.data_len / sizeof(float);

    // return base64 of raw float bytes (same bytes you were reading as float[])
    out.success = true;
    out.message = "registered";
    out.templateId = (int)registeredUsers_.size();
    out.palmType = r.palm_type;
    out.irCount = irCount;
    out.rgbCount = rgbCount;
    out.irFeaturesB64  = base64Encode((const uint8_t*)r.ir_features.data.get(), r.ir_features.data_len);
    out.rgbFeaturesB64 = base64Encode((const uint8_t*)r.rgb_features.data.get(), r.rgb_features.data_len);

    // Store internally too (optional)
    PalmTemplate tpl;
    tpl.palmType = r.palm_type;
    tpl.irFeatures.assign((const float*)r.ir_features.data.get(), (const float*)r.ir_features.data.get() + irCount);
    tpl.rgbFeatures.assign((const float*)r.rgb_features.data.get(), (const float*)r.rgb_features.data.get() + rgbCount);
    registeredUsers_.push_back(std::move(tpl));

    return out;
}



static std::vector<uint8_t> base64Decode(const std::string& in) {
    static const int T[256] = {
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,
        -1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,62,-1,-1,-1,63,
        52,53,54,55,56,57,58,59,60,61,-1,-1,-1,-2,-1,-1,
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9,10,11,12,13,14,
        15,16,17,18,19,20,21,22,23,24,25,-1,-1,-1,-1,-1,
        -1,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,
        41,42,43,44,45,46,47,48,49,50,51,-1,-1,-1,-1,-1,
        // rest are -1
    };
    std::vector<uint8_t> out;
    int val = 0, valb = -8;

    for (unsigned char c : in) {
        if (c > 127) continue;
        int d = T[c];
        if (d == -1) continue;
        if (d == -2) break;
        val = (val << 6) + d;
        valb += 6;
        if (valb >= 0) {
            out.push_back(uint8_t((val >> valb) & 0xFF));
            valb -= 8;
        }
    }
    return out;
}


static bool decodeFeaturesB64ToFloats(const std::string& b64, std::vector<float>& out) {
    out.clear();
    if (b64.empty()) return false;

    std::vector<uint8_t> bytes = base64Decode(b64);
    if (bytes.size() % sizeof(float) != 0) return false;

    const size_t n = bytes.size() / sizeof(float);
    out.resize(n);

    static_assert(sizeof(float) == 4, "float must be 4 bytes");
    std::memcpy(out.data(), bytes.data(), bytes.size());
    return true;
}


CaptureOnceOut PalmService::captureOnce(uint32_t timeoutMs) {
    CaptureOnceOut out;

    std::mutex m;
    std::condition_variable cv;
    std::atomic<bool> done{false};
    bool gotGood = false;
    StreamPalm::CapturePalmResult good{};
    StreamPalm::CapturePalmResult lastTerminal{};

    auto cb = [&](const StreamPalm::CapturePalmResult& r) {
        if (done.load(std::memory_order_acquire)) return;

        const bool hasFeatures =
            r.ir_features.data && r.ir_features.data_len > 0 &&
            r.rgb_features.data && r.rgb_features.data_len > 0;

        const bool isGood =
            (r.capture_handler_status == StreamPalm::PalmDetectStatus::kPalmDetected) &&
            (r.live_palm_errors == 0) &&
            hasFeatures;

        const bool isTerminal =
            (r.capture_handler_status == StreamPalm::PalmDetectStatus::kTimeout) ||
            (r.capture_handler_status == StreamPalm::PalmDetectStatus::kManualStop);

        if (isGood) {
            std::lock_guard<std::mutex> lk(m);
            good = r;
            gotGood = true;
            done.store(true, std::memory_order_release);
            cv.notify_one();
            return;
        }

        if (isTerminal) {
            std::lock_guard<std::mutex> lk(m);
            lastTerminal = r;
            done.store(true, std::memory_order_release);
            cv.notify_one();
        }
    };

    int ret = palm_->CapturePalmOnce(cb, timeoutMs);
    if (ret != 0) {
        out.success = false;
        out.message = "CapturePalmOnce start failed ret=" + std::to_string(ret);
        return out;
    }

    {
        std::unique_lock<std::mutex> lk(m);
        cv.wait_for(lk, std::chrono::milliseconds(timeoutMs + 500),
                    [&]{ return done.load(std::memory_order_acquire); });
    }

    palm_->StopPalmCapture(); // safe: callback ignores after done=true

    if (!done.load()) {
        out.success = false;
        out.message = "Timeout waiting for capture";
        return out;
    }
    if (!gotGood) {
        out.success = false;
        out.message = (lastTerminal.capture_handler_status == StreamPalm::PalmDetectStatus::kTimeout)
                        ? "Capture timeout"
                        : "Stopped before good palm captured";
        return out;
    }

    const auto& r = good;
    out.palmType = r.palm_type;

    size_t irCount  = r.ir_features.data_len / sizeof(float);
    size_t rgbCount = r.rgb_features.data_len / sizeof(float);

    const float* irPtr  = reinterpret_cast<const float*>(r.ir_features.data.get());
    const float* rgbPtr = reinterpret_cast<const float*>(r.rgb_features.data.get());

    out.ir.assign(irPtr, irPtr + irCount);
    out.rgb.assign(rgbPtr, rgbPtr + rgbCount);

    out.success = true;
    out.message = "OK";
    return out;
}

VerifyResponse PalmService::identify(uint32_t timeoutMs,
                                    const std::vector<CandidateTpl>& candidates) {
    std::lock_guard<std::mutex> lock(mutex_); // prevent concurrent capture

    VerifyResponse out;

    if (!palm_ || !comparer_) {
        out.success = false;
        out.message = "SDK not initialized (palm_/comparer_ is null)";
        return out;
    }

    // 1) Capture live features
    auto cap = captureOnce(timeoutMs);
    if (!cap.success) {
        out.success = false;
        out.message = cap.message;
        return out;
    }

    // 2) Thresholds
    float irTh = 0.f, rgbTh = 0.f;
    auto mode = StreamPalm::kBiModal;  // plain enum in this SDK

    int tret = palm_->GetRecognitionThreshold(irTh, rgbTh, mode);
    if (tret != 0) {
        out.success = false;
        out.message = "GetRecognitionThreshold failed ret=" + std::to_string(tret);
        return out;
    }

    // 3) Compare all candidates
    int bestTemplateId = -1;
    std::string bestFinCode;
    float bestIr = -1.f, bestRgb = -1.f;

    for (const auto& c : candidates) {
        if (c.templateId < 0) continue;

        std::vector<float> irStored, rgbStored;
        if (!decodeFeaturesB64ToFloats(c.irB64, irStored)) continue;
        if (!decodeFeaturesB64ToFloats(c.rgbB64, rgbStored)) continue;

        float irScore = 0.f, rgbScore = 0.f;

        int cret = comparer_->CompareFeaturesScore(
            irScore, rgbScore,
            cap.ir, irStored,
            cap.rgb, rgbStored,
            mode
        );
        if (cret != 0) continue;

        float combined = 0.5f * (irScore + rgbScore);
        float bestCombined = (bestTemplateId < 0) ? -1.f : 0.5f * (bestIr + bestRgb);

        if (combined > bestCombined) {
            bestTemplateId = c.templateId;
            bestFinCode = c.finCode;
            bestIr = irScore;
            bestRgb = rgbScore;
        }
    }

    // Fill response
    out.irThreshold = irTh;
    out.rgbThreshold = rgbTh;
    out.palmType = cap.palmType;

    if (bestTemplateId < 0) {
        out.success = false;
        out.message = "No match candidates";
        return out;
    }

    out.matchedTemplateId = bestTemplateId;
    out.matchedFinCode = bestFinCode;
    out.irScore = bestIr;
    out.rgbScore = bestRgb;

    // Accept rule: BOTH must pass
    if (bestIr >= irTh && bestRgb >= rgbTh) {
        out.success = true;
        out.message = "MATCH";
    } else {
        out.success = false;
        out.message = "NO_MATCH";
    }

    return out;
}











