package com.demo.stayintegration.supplier.adapter.support;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.demo.stayintegration.supplier.port.SupplierId;

// 공급사 id로 키잉한다. 어댑터는 기동 시 자기 항목을 꺼내고 없으면 즉시 실패한다 —
// 설정 누락을 첫 검색 요청의 NPE로 알게 되는 것보다 기동 실패가 낫다.
@ConfigurationProperties(prefix = "supplier")
public record SupplierProperties(Map<String, Endpoint> endpoints) {

	public SupplierProperties {
		endpoints = endpoints == null ? Map.of() : Map.copyOf(endpoints);
	}

	// 타임아웃에 기본값을 두지 않는 이유: 500ms/2s는 이 시스템의 핵심 설계값(D-7)이라 yaml에 근거와 함께 드러나 있어야 한다.
	public record Endpoint(String baseUrl, String apiKey, Duration connectTimeout, Duration responseTimeout) {

		public Endpoint {
			require(baseUrl != null && !baseUrl.isBlank(), "base-url");
			require(apiKey != null && !apiKey.isBlank(), "api-key");
			require(connectTimeout != null && connectTimeout.isPositive(), "connect-timeout");
			require(responseTimeout != null && responseTimeout.isPositive(), "response-timeout");
		}

		private static void require(boolean condition, String property) {
			if (!condition) {
				throw new IllegalArgumentException("supplier endpoint " + property + " must be set and positive");
			}
		}
	}

	public Endpoint endpointOf(SupplierId supplierId) {
		Endpoint endpoint = endpoints.get(supplierId.value());
		if (endpoint == null) {
			throw new IllegalStateException("supplier.endpoints." + supplierId + " is not configured; adapter " + supplierId + " cannot start");
		}
		return endpoint;
	}
}
