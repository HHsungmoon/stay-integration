package com.demo.stayintegration.supplier.adapter.support;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import reactor.core.publisher.Mono;

// A·B 어댑터가 공유하는 호출 골격: 서킷 권한 → 소요시간 측정 → 응답 타임아웃 → 판정 결과를 SupplierResult로 → 예외를 값으로 강등.
// 공급사 단위 인스턴스인 이유: 서킷이 공급사 단위이고 이 골격의 맨 바깥에 있다.
public final class SupplierCallPipeline {

	private final SupplierId supplierId;
	private final Duration responseTimeout;
	private final CircuitBreaker circuitBreaker;

	public SupplierCallPipeline(SupplierId supplierId, Duration responseTimeout, CircuitBreaker circuitBreaker) {
		this.supplierId = supplierId;
		this.responseTimeout = responseTimeout;
		this.circuitBreaker = circuitBreaker;
	}

	public <T> Mono<SupplierResult<T>> execute(Mono<CallOutcome<T>> call) {
		// defer인 이유: 구독마다 시작 시각을 새로 찍어야 한다. 조립 시점에 찍으면 재구독 시 소요시간이 누적된다.
		Mono<SupplierResult<T>> guarded = Mono.defer(() -> {
			long started = System.nanoTime();
			return call
					.timeout(responseTimeout)
					// 2xx인데 본문이 비어 있으면 exchangeToMono가 빈 Mono를 낸다. 그대로 두면 결과 자체가 사라져 병합에서 공급사가 증발한다.
					.switchIfEmpty(Mono.fromSupplier(() -> CallOutcome.failed(FailureKind.UNEXPECTED, "empty response")))
					.map(outcome -> toResult(outcome, elapsedSince(started)))
					.onErrorResume(error -> Mono.just(failure(error, elapsedSince(started))));
		});
		return guarded
				// 서킷은 맨 바깥. 권한이 거부되면 위 defer를 구독하지 않으므로 HTTP 호출도 소요시간 측정도 없다.
				// 실패는 위에서 이미 값이 되어 내려오고, 서킷은 그 값을 recordResult 술어로 판정한다(SupplierCallPipelines).
				.transformDeferred(CircuitBreakerOperator.<SupplierResult<T>>of(circuitBreaker))
				// 열림은 실패가 아니라 "호출하지 않음"이다. Failure로 강등하면 서킷의 보호 동작이 장애 지표로 보인다.
				.onErrorResume(CallNotPermittedException.class,
						denied -> Mono.just(new SupplierResult.Skipped<>(supplierId, "circuit open", Duration.ZERO)));
	}

	private <T> SupplierResult<T> toResult(CallOutcome<T> outcome, Duration elapsed) {
		return switch (outcome) {
			case CallOutcome.Ok<T> ok -> new SupplierResult.Success<>(supplierId, ok.value(), elapsed);
			case CallOutcome.Failed<T> failed -> new SupplierResult.Failure<>(supplierId, failed.kind(), failed.detail(), elapsed);
		};
	}

	private <T> SupplierResult<T> failure(Throwable error, Duration elapsed) {
		String detail = error instanceof TimeoutException
				? "no response within " + responseTimeout
				: FailureClassifier.describe(error);
		return new SupplierResult.Failure<>(supplierId, FailureClassifier.fromException(error), detail, elapsed);
	}

	private static Duration elapsedSince(long startedNanos) {
		return Duration.ofNanos(System.nanoTime() - startedNanos);
	}
}
