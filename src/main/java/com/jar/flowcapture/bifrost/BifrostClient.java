package com.jar.flowcapture.bifrost;

import com.jar.flowcapture.config.FlowCaptureProperties;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

/**
 * Thin client for Jar's internal Bifrost LLM gateway, used ONLY for judgment-call
 * pieces of codegen (method naming, draft assertions, ambiguous-locator tie-breaks)
 * — never for the structural skeleton, which StructuralCodegen produces deterministically.
 *
 * Disabled by default (flow-capture.bifrost.enabled=false in application.yml). The
 * tool is fully usable without this; enabling it is an opt-in quality upgrade, not
 * a hard dependency, in line with keeping generated structure 100% deterministic
 * and only using the model where real judgment is needed.
 */
@Slf4j
@Service
public class BifrostClient {

    private final FlowCaptureProperties properties;
    private final RestTemplate restTemplate = new RestTemplate();

    public BifrostClient(FlowCaptureProperties properties) {
        this.properties = properties;
    }

    public boolean isEnabled() {
        return properties.getBifrost().isEnabled() && !properties.getBifrost().getApiKey().isBlank();
    }

    /**
     * @param systemContext few-shot reference material: real file contents from the target
     *                      repo (e.g. MonthlySavingsLocators.java + MonthlySavingsPage.java),
     *                      so the model mimics concrete examples rather than a written style rule.
     * @param userPrompt the specific judgment call being asked, e.g. "suggest a method name for
     *                   this recorded action sequence" or "draft a soft-assert for this captured screen text"
     */
    public String ask(String systemContext, String userPrompt) {
        if (!isEnabled()) {
            throw new IllegalStateException("Bifrost is disabled — set flow-capture.bifrost.enabled=true and provide an API key to use AI-assisted codegen steps");
        }

        var bifrost = properties.getBifrost();

        JSONObject body = new JSONObject();
        body.put("model", bifrost.getModel());
        body.put("max_tokens", 1024);
        body.put("system", systemContext);
        body.put("messages", new JSONArray().put(new JSONObject()
                .put("role", "user")
                .put("content", userPrompt)));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", bifrost.getApiKey());
        headers.set("anthropic-version", "2023-06-01");

        RequestEntity<String> request = RequestEntity
                .post(URI.create(bifrost.getBaseUrl() + "/v1/messages"))
                .headers(headers)
                .body(body.toString());

        try {
            ResponseEntity<String> response = restTemplate.exchange(request, String.class);
            JSONObject json = new JSONObject(response.getBody());
            return json.getJSONArray("content").getJSONObject(0).getString("text");
        } catch (Exception e) {
            log.warn("Bifrost call failed, falling back to deterministic default: {}", e.getMessage());
            throw new IllegalStateException("Bifrost request failed", e);
        }
    }
}
