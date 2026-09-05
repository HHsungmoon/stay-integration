package com.demo.mocksupplier.common;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

// api-key.a / api-key.b — 공급사별로 다르게 둔다. 어댑터가 공급사별 설정을 제대로 쓰는지 함께 검증된다.
@ConfigurationProperties(prefix = "mock")
public record MockProperties(Map<String, String> apiKey, long delayDefaultMs) {

	public String apiKeyOf(String supplier) {
		return apiKey.get(supplier);
	}
}
