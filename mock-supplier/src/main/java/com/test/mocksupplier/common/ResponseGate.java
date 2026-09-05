package com.test.mocksupplier.common;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

import com.test.mocksupplier.control.MockModeRegistry;

@Component
public class ResponseGate implements DisposableBean {

	// no-response의 "무응답" 상한. Thread.sleep(600_000)이 아니라 DeferredResult라 스레드는 점유하지 않는다.
	// Long.MAX_VALUE를 쓰지 않는 이유: 컨테이너가 now + timeout을 계산할 때 오버플로우할 수 있다.
	private static final long NO_RESPONSE_HOLD_MS = 600_000L;

	private final MockModeRegistry registry;
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "mock-delay");
		t.setDaemon(true);
		return t;
	});

	public ResponseGate(MockModeRegistry registry) {
		this.registry = registry;
	}

	public DeferredResult<ResponseEntity<?>> respond(String supplier, String api,
			Supplier<ResponseEntity<?>> normal, Supplier<ResponseEntity<?>> error) {

		MockModeRegistry.State state = registry.get(supplier, api);
		DeferredResult<ResponseEntity<?>> result = new DeferredResult<>(NO_RESPONSE_HOLD_MS);

		switch (state.mode()) {
			case NORMAL -> result.setResult(normal.get());
			case ERROR -> result.setResult(error.get());
			case NO_RESPONSE -> registry.hold(result);   // 아무도 setResult를 부르지 않는다
			case DELAY -> scheduler.schedule(() -> result.setResult(normal.get()), state.delayMs(), TimeUnit.MILLISECONDS);
		}
		return result;
	}

	// 인증·검증 실패처럼 모드와 무관하게 즉시 거절할 때. 게이트를 거치지 않는다.
	public static DeferredResult<ResponseEntity<?>> immediate(ResponseEntity<?> response) {
		DeferredResult<ResponseEntity<?>> result = new DeferredResult<>();
		result.setResult(response);
		return result;
	}

	@Override
	public void destroy() {
		scheduler.shutdownNow();
	}
}
