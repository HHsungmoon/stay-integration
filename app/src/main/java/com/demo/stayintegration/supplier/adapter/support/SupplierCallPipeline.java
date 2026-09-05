package com.demo.stayintegration.supplier.adapter.support;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

import reactor.core.publisher.Mono;

// A·B 어댑터가 공유하는 호출 골격: 소요시간 측정 → 응답 타임아웃 → 판정 결과를 SupplierResult로 → 예외를 값으로 강등.
// 공급사 단위 인스턴스인 이유: 서킷 브레이커(7단계)가 공급사 단위이고 이 골격의 맨 바깥에 들어온다.
public final class SupplierCallPipeline {

	private final SupplierId supplierId;
	private final Duration responseTimeout;

	public SupplierCallPipeline(SupplierId supplierId, Duration responseTimeout) {
		this.supplierId = supplierId;
		this.responseTimeout = responseTimeout;
	}

	public <T> Mono<SupplierResult<T>> execute(Mono<CallOutcome<T>> call) {
		// defer인 이유: 구독마다 시작 시각을 새로 찍어야 한다. 조립 시점에 찍으면 재구독 시 소요시간이 누적된다.
		return Mono.defer(() -> {
			long started = System.nanoTime();
			return call
					.timeout(responseTimeout)
					// 2xx인데 본문이 비어 있으면 exchangeToMono가 빈 Mono를 낸다. 그대로 두면 결과 자체가 사라져 병합에서 공급사가 증발한다.
					.switchIfEmpty(Mono.fromSupplier(() -> CallOutcome.failed(FailureKind.UNEXPECTED, "empty response")))
					.map(outcome -> toResult(outcome, elapsedSince(started)))
					.onErrorResume(error -> Mono.just(failure(error, elapsedSince(started))));
		});
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
