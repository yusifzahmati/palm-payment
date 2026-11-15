#include <iostream>
#include <csignal>
#include <thread>

#include "httplib.h"
#include "PalmService.h"

#include "json.hpp"
using nlohmann::json;

static bool running = true;

void handleSignal(int) {
    running = false;
}

static std::string jsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"':  out += "\\\""; break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:   out += c;      break;
        }
    }
    return out;
}

int main() {
    std::signal(SIGINT, handleSignal);
    std::signal(SIGTERM, handleSignal);

    PalmService palmService;

    std::cout << "[PalmService] Initializing..." << std::endl;
    if (!palmService.init()) {
        std::cerr << "[PalmService] Initialization failed" << std::endl;
        return -1;
    }

    httplib::Server server;




    server.Get("/health",
        [](const httplib::Request&, httplib::Response& res) {
            res.set_content(R"({"status":"UP"})", "application/json");
        }
    );


        server.Post("/palm/identify", [&](const httplib::Request& req, httplib::Response& res) {
        try {
            json j = json::parse(req.body);

            uint32_t timeoutMs = (uint32_t)j.value("timeoutMs", 8000);

            std::vector<CandidateTpl> candidates;
            if (!j.contains("candidates") || !j["candidates"].is_array()) {
                res.status = 400;
                res.set_content(R"({"success":false,"message":"Missing candidates array"})", "application/json");
                return;
            }

            for (const auto& item : j["candidates"]) {
                CandidateTpl c;
                c.finCode = item.value("finCode", "");
                c.templateId = item.value("templateId", -1);
                c.irB64 = item.value("irFeatureBase64", "");
                c.rgbB64 = item.value("rgbFeatureBase64", "");

                if (c.templateId >= 0 && !c.irB64.empty() && !c.rgbB64.empty()) {
                    candidates.push_back(std::move(c));
                }
            }

            if (candidates.empty()) {
                res.status = 400;
                res.set_content(R"({"success":false,"message":"No valid candidates"})", "application/json");
                return;
            }

            VerifyResponse vr = palmService.identify(timeoutMs, candidates);

            json out = {
                {"success", vr.success},
                {"message", vr.message},
                {"matchedTemplateId", vr.matchedTemplateId},
                {"matchedFinCode", vr.matchedFinCode},
                {"irScore", vr.irScore},
                {"rgbScore", vr.rgbScore},
                {"irThreshold", vr.irThreshold},
                {"rgbThreshold", vr.rgbThreshold},
                {"palmType", vr.palmType}
            };

            res.set_content(out.dump(), "application/json");
        } catch (const std::exception& e) {
            res.status = 400;
            std::string msg = std::string("Bad JSON: ") + e.what();
            res.set_content(
                std::string("{\"success\":false,\"message\":\"") + jsonEscape(msg) + "\"}",
                "application/json"
            );
        }
    });



//     server.Post("/palm/register", [&](const httplib::Request&, httplib::Response& res) {
//     bool ok = palmService.registerPalm(8000);
//     res.set_content(ok ? R"({"registered":true})"
//                        : R"({"registered":false})",
//                     "application/json");
// });


    server.Post("/palm/register", [&](const httplib::Request&, httplib::Response& res) {
    auto rr = palmService.registerUserPalm(8000);

    json j = {
        {"success", rr.success},
        {"message", rr.message},
        {"templateId", rr.templateId},
        {"palmType", rr.palmType},
        {"irCount", rr.irCount},
        {"rgbCount", rr.rgbCount},
        {"irFeaturesB64", rr.irFeaturesB64},
        {"rgbFeaturesB64", rr.rgbFeaturesB64},
    };

    res.set_content(j.dump(), "application/json");
});




    std::cout << "[PalmService] HTTP server running on port 8081" << std::endl;

    std::thread serverThread([&]() {
        server.listen("0.0.0.0", 8081);
    });
    while (running) {
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }

    std::cout << "[PalmService] Shutting down..." << std::endl;
    server.stop();
    serverThread.join();

    return 0;
}
