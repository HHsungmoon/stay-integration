package com.demo.stayintegration.supplier.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.demo.mocksupplier.control.MockMode;
import com.demo.stayintegration.MockSupplierServer;
import com.demo.stayintegration.supplier.adapter.a.SupplierAAdapter;
import com.demo.stayintegration.supplier.adapter.b.SupplierBAdapter;
import com.demo.stayintegration.supplier.adapter.support.SupplierCallPipelines;
import com.demo.stayintegration.supplier.adapter.support.SupplierProperties;
import com.demo.stayintegration.supplier.adapter.support.SupplierProperties.Endpoint;
import com.demo.stayintegration.supplier.adapter.support.SupplierWebClients;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.Offer;
import com.demo.stayintegration.supplier.port.SupplierResult;
import com.demo.stayintegration.supplier.port.SupplierResult.Failure;
import com.demo.stayintegration.supplier.port.SupplierResult.Success;

// 스텁이 못 하는 것 — 실제 타임아웃·연결 거부·HTTP 인코딩을 거친 본문 — 을 실제 와이어로 본다. Spring 컨텍스트 없이 어댑터만 조립한다.
// 응답 타임아웃을 700ms로 줄인 이유: 무응답 케이스를 2초씩 기다리지 않기 위해. 값이 아니라 경로를 검증한다.
class SupplierAdapterWireTest {

	static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(700);
	static final AvailabilityQuery THREE_NIGHTS_A = new AvailabilityQuery(List.of("A-10023", "A-10044"),
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0);
	static final AvailabilityQuery THREE_NIGHTS_B = new AvailabilityQuery(List.of("B77120"),
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0);

	static MockSupplierServer mock;
	static SupplierAAdapter adapterA;
	static SupplierBAdapter adapterB;

	@BeforeAll
	static void startMockAndBuildAdapters() {
		mock = MockSupplierServer.start();
		SupplierProperties properties = properties(mock.baseUrl(), "mock-key-a", "mock-key-b");
		SupplierWebClients webClients = new SupplierWebClients(WebClient.builder(), properties);
		SupplierCallPipelines pipelines = new SupplierCallPipelines(properties);
		adapterA = new SupplierAAdapter(webClients, pipelines);
		adapterB = new SupplierBAdapter(webClients, pipelines);
	}

	@AfterEach
	void resetMock() {
		mock.reset();
	}

	@Test
	void availabilityOfSupplierAOverRealHttp() {
		Success<AvailabilityResult> success = success(adapterA.fetchAvailability(THREE_NIGHTS_A).block());

		AvailabilityResult result = success.value();
		assertThat(result.rejected()).isEmpty();
		assertThat(result.offers()).extracting(Offer::hotelCode, Offer::roomTypeCode, Offer::availableRooms, o -> o.price().totalAmount())
				.containsExactlyInAnyOrder(
						tuple("A-10023", "DLX-TWN", 1, 429_000L),   // [3,1,5] → 1, Σ(net+tax)
						tuple("A-10023", "STD-DBL", 4, 297_000L),
						tuple("A-10023", "FAM-STE", 2, 704_000L),
						tuple("A-10044", "STD-DBL", 0, 302_500L));  // [2,0,4] → 연박이면 매진
		assertThat(result.offers()).allSatisfy(offer -> assertThat(offer.priceDetail().nightlyBreakdown()).hasSize(3));
	}

	@Test
	void availabilityOfSupplierBOverRealHttp() {
		Success<AvailabilityResult> success = success(adapterB.fetchAvailability(THREE_NIGHTS_B).block());

		assertThat(success.value().rejected()).isEmpty();
		assertThat(success.value().offers()).extracting(Offer::roomTypeCode, Offer::availableRooms, o -> o.price().totalAmount())
				.containsExactlyInAnyOrder(
						tuple("R-401", 1, 465_000L),
						tuple("R-402", 1, 780_000L));
		assertThat(success.value().offers()).allSatisfy(offer -> {
			assertThat(offer.priceDetail()).isNull();
			assertThat(offer.price().breakfastIncluded()).isTrue();
		});
	}

	@Test
	void catalogOfBothSuppliersOverRealHttp() {
		List<CatalogProperty> a = success(adapterA.fetchCatalog().block()).value();
		List<CatalogProperty> b = success(adapterB.fetchCatalog().block()).value();

		assertThat(a).extracting(CatalogProperty::code).containsExactly("A-10023", "A-10044");
		assertThat(a.get(0).roomTypes()).hasSize(3);
		assertThat(b).extracting(CatalogProperty::code).containsExactly("B77120");
		assertThat(b.get(0).roomTypes()).hasSize(2);
	}

	@Test
	void supplierAErrorModeIs503AndClassifiedAsServerError() {
		mock.setMode("a", "availability", MockMode.ERROR);

		Failure<AvailabilityResult> failure = failure(adapterA.fetchAvailability(THREE_NIGHTS_A).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(failure.detail()).isEqualTo("HTTP 503 SERVICE_UNAVAILABLE: temporarily unavailable");
	}

	@Test
	void supplierBErrorModeIsHttp200ButClassifiedAsTheSameServerError() {
		mock.setMode("b", "availability", MockMode.ERROR);

		Failure<AvailabilityResult> failure = failure(adapterB.fetchAvailability(THREE_NIGHTS_B).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(failure.detail()).isEqualTo("E503 TEMPORARILY_UNAVAILABLE");
	}

	@Test
	void noResponseIsTimeoutWithinTheConfiguredResponseTimeout() {
		mock.setMode("a", "availability", MockMode.NO_RESPONSE);

		Failure<AvailabilityResult> failure = failure(adapterA.fetchAvailability(THREE_NIGHTS_A).block(Duration.ofSeconds(5)));

		assertThat(failure.kind()).isEqualTo(FailureKind.TIMEOUT);
		assertThat(failure.elapsed()).isGreaterThanOrEqualTo(RESPONSE_TIMEOUT).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void delayLongerThanTheResponseTimeoutIsAlsoTimeout() {
		mock.delay("b", "catalog", 3_000);

		Failure<List<CatalogProperty>> failure = failure(adapterB.fetchCatalog().block(Duration.ofSeconds(5)));

		assertThat(failure.kind()).isEqualTo(FailureKind.TIMEOUT);
	}

	@Test
	void nothingListeningIsConnectionFailed() throws IOException {
		SupplierProperties closedPort = properties("http://localhost:" + freePort(), "mock-key-a", "mock-key-b");
		SupplierAAdapter unreachable = new SupplierAAdapter(new SupplierWebClients(WebClient.builder(), closedPort), new SupplierCallPipelines(closedPort));

		Failure<List<CatalogProperty>> failure = failure(unreachable.fetchCatalog().block(Duration.ofSeconds(5)));

		assertThat(failure.kind()).isEqualTo(FailureKind.CONNECTION_FAILED);
		assertThat(failure.detail()).contains("Connection refused");
	}

	@Test
	void wrongApiKeyIsUnauthorizedForBothFailureStyles() {
		SupplierProperties wrongKeys = properties(mock.baseUrl(), "nope", "nope");
		SupplierWebClients webClients = new SupplierWebClients(WebClient.builder(), wrongKeys);

		Failure<List<CatalogProperty>> a = failure(new SupplierAAdapter(webClients, new SupplierCallPipelines(wrongKeys)).fetchCatalog().block());
		Failure<List<CatalogProperty>> b = failure(new SupplierBAdapter(webClients, new SupplierCallPipelines(wrongKeys)).fetchCatalog().block());

		assertThat(a.kind()).isEqualTo(FailureKind.UNAUTHORIZED);   // HTTP 401
		assertThat(a.detail()).startsWith("HTTP 401 UNAUTHORIZED");
		assertThat(b.kind()).isEqualTo(FailureKind.UNAUTHORIZED);   // HTTP 200 + E401
		assertThat(b.detail()).startsWith("E401");
	}

	// ── helpers ───────────────────────────────────────────────────────────

	static SupplierProperties properties(String baseUrl, String keyA, String keyB) {
		return new SupplierProperties(Map.of(
				"a", new Endpoint(baseUrl, keyA, Duration.ofMillis(500), RESPONSE_TIMEOUT),
				"b", new Endpoint(baseUrl, keyB, Duration.ofMillis(500), RESPONSE_TIMEOUT)), StubExchange.CIRCUIT_THAT_NEVER_OPENS);
	}

	static int freePort() throws IOException {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
	}

	@SuppressWarnings("unchecked")
	static <T> Success<T> success(SupplierResult<T> result) {
		assertThat(result).describedAs("expected Success but was %s", result).isInstanceOf(Success.class);
		return (Success<T>) result;
	}

	@SuppressWarnings("unchecked")
	static <T> Failure<T> failure(SupplierResult<T> result) {
		assertThat(result).describedAs("expected Failure but was %s", result).isInstanceOf(Failure.class);
		return (Failure<T>) result;
	}
}
