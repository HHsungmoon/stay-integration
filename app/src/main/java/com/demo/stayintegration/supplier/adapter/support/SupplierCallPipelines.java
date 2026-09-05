package com.demo.stayintegration.supplier.adapter.support;

import org.springframework.stereotype.Component;

import com.demo.stayintegration.supplier.adapter.support.SupplierProperties.Circuit;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.SlidingWindowType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;

// 어댑터가 파이프라인을 직접 조립하면 타임아웃·서킷 조립 코드가 공급사마다 한 벌씩 생긴다. SupplierWebClients와 같은 모양으로
// 공급사 id만 주면 설정된 파이프라인이 나오게 해, 신규 공급사가 서킷을 자동으로 얻는다.
// 서킷은 공급사당 하나(카탈로그·재고 공유): 지배적 실패(연결 거부·타임아웃·인증)가 호스트 수준이고, 카탈로그 호출만으로는 창이 영원히 안 찬다.
@Component
@Slf4j
public class SupplierCallPipelines {

	private final SupplierProperties supplierProperties;
	private final CircuitBreakerRegistry circuitBreakerRegistry;

	public SupplierCallPipelines(SupplierProperties supplierProperties) {
		this.supplierProperties = supplierProperties;
		this.circuitBreakerRegistry = CircuitBreakerRegistry.of(circuitBreakerConfig(supplierProperties.circuit()));
		// 전이만 남긴다 — 호출마다 찍지 않는다. 전이는 드물고 그 순간이 사고 조사의 시작점이다.
		circuitBreakerRegistry.getEventPublisher().onEntryAdded(added -> added.getAddedEntry().getEventPublisher()
				.onStateTransition(event -> log.atInfo().addKeyValue("supplier", event.getCircuitBreakerName()).addKeyValue("event", "circuit_transition")
						.addKeyValue("from", event.getStateTransition().getFromState().name()).addKeyValue("to", event.getStateTransition().getToState().name())
						.log("circuit breaker {}: {} -> {}", event.getCircuitBreakerName(),
								event.getStateTransition().getFromState(), event.getStateTransition().getToState())));
	}

	public SupplierCallPipeline forSupplier(SupplierId supplierId) {
		return new SupplierCallPipeline(supplierId, supplierProperties.endpointOf(supplierId).responseTimeout(),
				circuitBreakerRegistry.circuitBreaker(supplierId.value()));
	}

	// 낱개가 아니라 레지스트리로 드는 이유: 8단계가 TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry)로 전 공급사 지표를 한 번에 붙인다.
	public CircuitBreakerRegistry registry() {
		return circuitBreakerRegistry;
	}

	static CircuitBreakerConfig circuitBreakerConfig(Circuit circuit) {
		return CircuitBreakerConfig.custom()
				// 시간 창은 트래픽이 없을 때 비어 판정이 흔들린다. 호출 수는 결정적이라 테스트도 시계 없이 재현된다.
				.slidingWindowType(SlidingWindowType.COUNT_BASED)
				.slidingWindowSize(circuit.slidingWindowSize())
				.minimumNumberOfCalls(circuit.minimumNumberOfCalls())
				.failureRateThreshold(circuit.failureRateThreshold())
				.waitDurationInOpenState(circuit.waitDurationInOpenState())
				.permittedNumberOfCallsInHalfOpenState(circuit.permittedNumberOfCallsInHalfOpenState())
				// 켜면 열림 상태가 공유 스케줄러에 전이를 예약한다. 반열림으로 갈 시점은 다음 호출이 왔을 때면 충분하다 —
				// 호출이 없으면 회복 여부를 알아낼 이유도 없다.
				.automaticTransitionFromOpenToHalfOpenEnabled(false)
				// 어댑터는 실패를 예외가 아니라 값으로 돌려준다. 이 술어가 없으면 서킷은 모든 Failure를 성공으로 기록해 영원히 열리지 않는다.
				// 기록 기준은 retryable()과 같은 질문("공급사 쪽 문제인가")이다 — 우리 버그(BAD_REQUEST)나 모르는 실패(UNEXPECTED)로
				// 정상 공급사를 끊지 않고, 401을 서킷 뒤에 숨겨 키 설정 오류라는 진짜 원인을 지우지 않는다.
				.recordResult(result -> result instanceof SupplierResult.Failure<?> failure && failure.kind().retryable())
				.build();
	}
}
