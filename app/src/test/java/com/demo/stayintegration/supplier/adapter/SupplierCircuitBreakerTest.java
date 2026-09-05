package com.demo.stayintegration.supplier.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.demo.stayintegration.supplier.adapter.a.SupplierAAdapter;
import com.demo.stayintegration.supplier.adapter.support.SupplierCallPipelines;
import com.demo.stayintegration.supplier.adapter.support.SupplierProperties.Circuit;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierResult;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;

// 서킷을 실제 어댑터 + StubExchange 조합으로 본다. "호출하지 않았다"는 결과 타입이 아니라 StubExchange.requests 수로 증명한다.
// 창 4 / 최소 3 / 50% / 반열림 3. 최소 호출 수를 반열림 허용 수 이상으로 둔 이유: 반열림 통계의 최소 호출 수는 min(허용 수, 최소 호출 수)라
// 허용된 셋이 모두 기록된 뒤 판정된다 — 07 §6의 "1건 실패는 닫힘, 2건이면 다시 열림"이 이 조건에서 성립한다.
class SupplierCircuitBreakerTest {

	private static final Circuit SMALL = new Circuit(4, 3, 50f, Duration.ofSeconds(10), 3);
	private static final AvailabilityQuery QUERY = new AvailabilityQuery(List.of("A-10023"),
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0);
	private static final String SERVICE_UNAVAILABLE_BODY = "{\"error\":\"SERVICE_UNAVAILABLE\",\"message\":\"temporarily unavailable\"}";
	private static final String UNAUTHORIZED_BODY = "{\"error\":\"UNAUTHORIZED\",\"message\":\"invalid api key\"}";
	private static final String EMPTY_AVAILABILITY = "{\"items\":[]}";

	private StubExchange exchange;
	private SupplierAAdapter adapter;
	private CircuitBreaker breaker;

	@BeforeEach
	void buildAdapterWithASmallCircuit() {
		exchange = new StubExchange();
		SupplierCallPipelines pipelines = new SupplierCallPipelines(StubExchange.properties("a", "mock-key-a", SMALL));
		adapter = new SupplierAAdapter(exchange.webClients("a", "mock-key-a"), pipelines);
		breaker = pipelines.registry().circuitBreaker("a");
	}

	private SupplierResult<AvailabilityResult> call() {
		return adapter.fetchAvailability(QUERY).block(Duration.ofSeconds(5));
	}

	private void openTheCircuit() {
		exchange.respond(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE_BODY);
		for (int i = 0; i < 3; i++) {
			assertThat(call()).isInstanceOf(SupplierResult.Failure.class);
		}
		assertThat(breaker.getState()).isEqualTo(State.OPEN);
	}

	@Test
	void repeatedRetryableFailuresOpenTheCircuitAndTheNextCallIsSkippedWithoutAnyRequest() {
		openTheCircuit();
		int requestsSoFar = exchange.requests.size();

		SupplierResult<AvailabilityResult> result = call();

		assertThat(result).isInstanceOfSatisfying(SupplierResult.Skipped.class, skipped -> {
			assertThat(skipped.reason()).isEqualTo("circuit open");
			assertThat(skipped.elapsed()).isZero();
		});
		assertThat(exchange.requests).hasSize(requestsSoFar);   // 여기가 증명이다 — 결과 타입이 아니라 요청 수
	}

	@Test
	void nonRetryableFailuresAreNotRecordedSoTheCircuitStaysClosed() {
		// 401은 우리 키 설정 오류다. 서킷 뒤에 숨기면 응답의 원인이 "circuit open"으로 바뀌어 진짜 원인이 사라진다.
		exchange.respond(HttpStatus.UNAUTHORIZED, UNAUTHORIZED_BODY);

		for (int i = 0; i < 6; i++) {
			assertThat(call()).isInstanceOfSatisfying(SupplierResult.Failure.class,
					failure -> assertThat(failure.kind()).isEqualTo(FailureKind.UNAUTHORIZED));
		}

		assertThat(breaker.getState()).isEqualTo(State.CLOSED);
		assertThat(exchange.requests).hasSize(6);
	}

	@Test
	void preRejectedOversizedQueriesNeverTouchTheCircuit() {
		AvailabilityQuery oversized = new AvailabilityQuery(IntStream.range(0, AvailabilityQuery.MAX_CODES + 1).mapToObj(i -> "A-" + i).toList(),
				QUERY.checkIn(), QUERY.checkOut(), 2, 0);

		for (int i = 0; i < 6; i++) {
			assertThat(adapter.fetchAvailability(oversized).block()).isInstanceOfSatisfying(SupplierResult.Failure.class,
					failure -> assertThat(failure.kind()).isEqualTo(FailureKind.BAD_REQUEST));
		}

		assertThat(breaker.getState()).isEqualTo(State.CLOSED);
		assertThat(breaker.getMetrics().getNumberOfBufferedCalls()).isZero();   // 호출하지 않은 것은 호출 통계에 없다
		assertThat(exchange.requests).isEmpty();
	}

	@Test
	void halfOpenClosesWhenTheFailureRateOfPermittedCallsStaysBelowThreshold() {
		openTheCircuit();
		breaker.transitionToHalfOpenState();   // 대기시간을 기다리지 않고 결정적으로

		exchange.respond(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE_BODY);
		assertThat(call()).isInstanceOf(SupplierResult.Failure.class);
		exchange.respond(HttpStatus.OK, EMPTY_AVAILABILITY);
		assertThat(call()).isInstanceOf(SupplierResult.Success.class);
		assertThat(breaker.getState()).isEqualTo(State.HALF_OPEN);   // 셋이 다 기록되기 전에는 판정하지 않는다
		assertThat(call()).isInstanceOf(SupplierResult.Success.class);

		assertThat(breaker.getState()).isEqualTo(State.CLOSED);   // 3건 중 1건 실패(33%) < 50%
	}

	@Test
	void halfOpenReopensWhenTheFailureRateOfPermittedCallsReachesThreshold() {
		openTheCircuit();
		breaker.transitionToHalfOpenState();

		exchange.respond(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE_BODY);
		assertThat(call()).isInstanceOf(SupplierResult.Failure.class);
		exchange.respond(HttpStatus.OK, EMPTY_AVAILABILITY);
		assertThat(call()).isInstanceOf(SupplierResult.Success.class);
		exchange.respond(HttpStatus.SERVICE_UNAVAILABLE, SERVICE_UNAVAILABLE_BODY);
		assertThat(call()).isInstanceOf(SupplierResult.Failure.class);

		assertThat(breaker.getState()).isEqualTo(State.OPEN);   // 3건 중 2건 실패(66%) >= 50%
		assertThat(call()).isInstanceOf(SupplierResult.Skipped.class);
	}

	@Test
	void cancellingBeforeAnyResultReleasesTheHalfOpenPermit() {
		// 06의 take(deadline)는 늦은 청크를 실제로 취소한다. 취소가 허용 수를 소진시키면 서킷이 반열림에 갇힌다 — 라이브러리 동작이지만 우리 조합에서 확인한다.
		openTheCircuit();
		breaker.transitionToHalfOpenState();
		exchange.neverRespond();

		for (int i = 0; i < SMALL.permittedNumberOfCallsInHalfOpenState(); i++) {
			adapter.fetchAvailability(QUERY).subscribe().dispose();
		}

		assertThat(breaker.getState()).isEqualTo(State.HALF_OPEN);
		exchange.respond(HttpStatus.OK, EMPTY_AVAILABILITY);
		assertThat(call()).isInstanceOf(SupplierResult.Success.class);   // 권한이 돌아왔으므로 Skipped가 아니다
	}
}
