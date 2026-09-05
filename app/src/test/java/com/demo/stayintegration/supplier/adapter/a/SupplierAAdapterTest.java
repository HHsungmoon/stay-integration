package com.demo.stayintegration.supplier.adapter.a;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.demo.stayintegration.supplier.adapter.StubExchange;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.CatalogRoomType;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.Offer;
import com.demo.stayintegration.supplier.port.SupplierResult;
import com.demo.stayintegration.supplier.port.SupplierResult.Failure;
import com.demo.stayintegration.supplier.port.SupplierResult.Success;

// A는 HTTP 상태로 실패를 알린다. 상태 코드 → FailureKind 매트릭스와, 어댑터가 예외를 절대 밖으로 내지 않는다는 계약을 본다.
class SupplierAAdapterTest {

	static final AvailabilityQuery QUERY = new AvailabilityQuery(List.of("A-10023", "A-10044"),
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0);

	StubExchange exchange;
	SupplierAAdapter adapter;

	@BeforeEach
	void setUp() {
		exchange = new StubExchange();
		adapter = new SupplierAAdapter(exchange.webClients("a", "mock-key-a"), exchange.pipelines("a", "mock-key-a"));
	}

	@Test
	void sendsApiKeyHeaderAndQueryParametersInTheSupplierFormat() {
		exchange.respond(HttpStatus.OK, """
				{"items": []}""");

		adapter.fetchAvailability(QUERY).block();

		ClientRequest request = exchange.onlyRequest();
		assertThat(request.method()).isEqualTo(HttpMethod.GET);
		assertThat(request.headers().getFirst("X-Api-Key")).isEqualTo("mock-key-a");
		assertThat(request.url().toString()).isEqualTo(
				StubExchange.BASE_URL + "/a/v1/availability?hotelCodes=A-10023,A-10044&checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0");
	}

	@Test
	void okResponseBecomesSuccessWithNormalizedOffers() {
		exchange.respond(HttpStatus.OK, """
				{"items": [{
				  "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
				  "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin",
				  "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
				  "dailyRates": [
				    {"date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 120000, "taxAmount": 12000},
				    {"date": "2026-09-02", "remainingRooms": 1, "nightlyRate": 150000, "taxAmount": 15000},
				    {"date": "2026-09-03", "remainingRooms": 5, "nightlyRate": 120000, "taxAmount": 12000}
				  ]}]}""");

		SupplierResult<AvailabilityResult> result = adapter.fetchAvailability(QUERY).block();

		Success<AvailabilityResult> success = (Success<AvailabilityResult>) result;
		assertThat(success.supplier()).isEqualTo(SupplierAAdapter.ID);
		assertThat(success.elapsed()).isPositive();
		Offer offer = success.value().offers().get(0);
		assertThat(offer.availableRooms()).isEqualTo(1);
		assertThat(offer.price().totalAmount()).isEqualTo(429_000);
		assertThat(offer.priceDetail().taxAmount()).isEqualTo(39_000);
	}

	@Test
	void catalogOkResponseBecomesProperties() {
		exchange.respond(HttpStatus.OK, """
				{"items": [{"hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
				            "roomTypes": [{"roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin", "maxOccupancy": 2}]}]}""");

		SupplierResult<List<CatalogProperty>> result = adapter.fetchCatalog().block();

		assertThat(exchange.onlyRequest().url()).isEqualTo(URI.create(StubExchange.BASE_URL + "/a/v1/hotels"));
		assertThat(((Success<List<CatalogProperty>>) result).value())
				.containsExactly(new CatalogProperty("A-10023", "Riverside Hotel Seoul",
						List.of(new CatalogRoomType("DLX-TWN", "Deluxe Twin", 2))));
	}

	@Test
	void serverErrorsBecomeServerErrorWithTheErrorBodyInDetail() {
		exchange.respond(HttpStatus.SERVICE_UNAVAILABLE, """
				{"error": "SERVICE_UNAVAILABLE", "message": "temporarily unavailable"}""");

		Failure<AvailabilityResult> failure = failure(adapter.fetchAvailability(QUERY).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(failure.detail()).isEqualTo("HTTP 503 SERVICE_UNAVAILABLE: temporarily unavailable");
		assertThat(failure.elapsed()).isPositive();
	}

	@Test
	void statusMatrix() {
		assertThat(kindOf(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(kindOf(HttpStatus.TOO_MANY_REQUESTS)).isEqualTo(FailureKind.RATE_LIMITED);
		assertThat(kindOf(HttpStatus.UNAUTHORIZED)).isEqualTo(FailureKind.UNAUTHORIZED);
		assertThat(kindOf(HttpStatus.BAD_REQUEST)).isEqualTo(FailureKind.BAD_REQUEST);
		assertThat(kindOf(HttpStatus.NOT_FOUND)).isEqualTo(FailureKind.UNEXPECTED);
	}

	@Test
	void errorWithUnreadableBodyStillClassifiesByStatus() {
		exchange.respondWithoutContentType(HttpStatus.BAD_GATEWAY, "<html>502 Bad Gateway</html>");

		Failure<AvailabilityResult> failure = failure(adapter.fetchAvailability(QUERY).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(failure.detail()).startsWith("HTTP 502");
	}

	@Test
	void brokenJsonOnOkIsUnexpectedNotAnException() {
		exchange.respond(HttpStatus.OK, "{\"items\": [{\"hotelCode\": ");

		Failure<AvailabilityResult> failure = failure(adapter.fetchAvailability(QUERY).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.UNEXPECTED);
	}

	@Test
	void okWithEmptyBodyIsUnexpected() {
		exchange.respondEmpty(HttpStatus.OK);

		Failure<AvailabilityResult> failure = failure(adapter.fetchAvailability(QUERY).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.UNEXPECTED);
		assertThat(failure.detail()).isEqualTo("empty response");
	}

	@Test
	void okWithoutItemsFieldIsUnexpected() {
		// items가 없는 응답을 빈 카탈로그로 읽으면 동기화가 매핑을 전부 비활성화한다
		exchange.respond(HttpStatus.OK, "{}");

		Failure<List<CatalogProperty>> failure = failure(adapter.fetchCatalog().block());

		assertThat(failure.kind()).isEqualTo(FailureKind.UNEXPECTED);
		assertThat(failure.detail()).contains("no items");
	}

	@Test
	void slowResponseIsTimeoutFromTheReactorLayer() {
		exchange.neverRespond();

		Failure<AvailabilityResult> failure = failure(adapter.fetchAvailability(QUERY).block(Duration.ofSeconds(5)));

		assertThat(failure.kind()).isEqualTo(FailureKind.TIMEOUT);
		assertThat(failure.detail()).contains(StubExchange.RESPONSE_TIMEOUT.toString());
		assertThat(failure.elapsed()).isGreaterThanOrEqualTo(StubExchange.RESPONSE_TIMEOUT);
	}

	@Test
	void connectionRefusedIsConnectionFailed() {
		exchange.fail(new WebClientRequestException(new ConnectException("Connection refused"),
				HttpMethod.GET, URI.create(StubExchange.BASE_URL), new HttpHeaders()));

		Failure<AvailabilityResult> failure = failure(adapter.fetchAvailability(QUERY).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.CONNECTION_FAILED);
		assertThat(failure.detail()).isEqualTo("ConnectException: Connection refused");
	}

	@Test
	void moreThanFiftyCodesIsRejectedWithoutCallingTheSupplier() {
		AvailabilityQuery tooMany = new AvailabilityQuery(Collections.nCopies(51, "A-1"),
				QUERY.checkIn(), QUERY.checkOut(), 2, 0);

		Failure<AvailabilityResult> failure = failure(adapter.fetchAvailability(tooMany).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.BAD_REQUEST);
		assertThat(failure.detail()).contains("51");
		assertThat(exchange.requests).isEmpty();
	}

	@Test
	void exactlyFiftyCodesIsSent() {
		exchange.respond(HttpStatus.OK, "{\"items\": []}");
		AvailabilityQuery fifty = new AvailabilityQuery(Collections.nCopies(50, "A-1"), QUERY.checkIn(), QUERY.checkOut(), 2, 0);

		adapter.fetchAvailability(fifty).block();

		assertThat(exchange.requests).hasSize(1);
	}

	private FailureKind kindOf(HttpStatus status) {
		exchange.respond(status, "{\"error\": \"X\", \"message\": \"y\"}");
		return failure(adapter.fetchAvailability(QUERY).block()).kind();
	}

	@SuppressWarnings("unchecked")
	private static <T> Failure<T> failure(SupplierResult<T> result) {
		assertThat(result).isInstanceOf(Failure.class);
		return (Failure<T>) result;
	}
}
