package com.localllm.locie.model;

import java.util.List;
import java.util.Map;

public record ModelDetailView(
        String name,
        String family,
        String format,
        String parameterSize,
        String quantization,
        List<String> capabilities,
        String systemPrompt,
        String parameters,
        Map<String, Object> modelInfo) {
}
