package com.demo.stayintegration.supplier.adapter.support;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;

// MeterBinder 빈은 Boot(MeterRegistryPostProcessor)가 레지스트리에 묶는다. 생성자에서 bindTo를 직접 부르지 않는 이유:
// SupplierCallPipelines가 MeterRegistry를 알 필요가 없고, 레지스트리가 없는 컨텍스트(어댑터 단위 테스트)에서도 그대로 뜬다.
// 여기(어댑터 인프라)에 두는 이유: 서킷 레지스트리는 어댑터의 것이고, common이 supplier.adapter를 알면 경계 2의 정신이 흐려진다.
@Configuration(proxyBeanMethods = false)
class SupplierCircuitMetricsConfig {

	@Bean
	MeterBinder supplierCircuitBreakerMetrics(SupplierCallPipelines supplierCallPipelines) {
		// 지표 이름은 Resilience4j 기본(resilience4j.circuitbreaker.state 등). 태그 name이 공급사 id다 — 07이 그 이름으로 서킷을 만든 덕이다
		return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(supplierCallPipelines.registry());
	}
}
