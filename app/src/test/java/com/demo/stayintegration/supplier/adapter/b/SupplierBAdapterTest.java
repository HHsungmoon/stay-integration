package com.demo.stayintegration.supplier.adapter.b;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;

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

// B는 항상 HTTP 200이다. 본문 resultCode를 보지 않으면 장애가 "빈 결과"로 둔갑한다 — 그 판정이 이 테스트의 본체다.
class SupplierBAdapterTest {

	static final AvailabilityQuery QUERY = new AvailabilityQuery(List.of("B77120"),
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0);

	StubExchange exchange;
	SupplierBAdapter adapter;

	@BeforeEach
	void setUp() {
		exchange = new StubExchange();
		adapter = new SupplierBAdapter(exchange.webClients("b", "mock-key-b"), StubExchange.properties("b", "mock-key-b"));
	}

	@Test
	void sendsApiKeyHeaderAndPropertyIdsParameter() {
		exchange.respond(HttpStatus.OK, """
				{"resultCode": "0000", "resultMessage": "SUCCESS", "data": {"items": []}}""");

		adapter.fetchAvailability(QUERY).block();

		ClientRequest request = exchange.onlyRequest();
		assertThat(request.headers().getFirst("X-Api-Key")).isEqualTo("mock-key-b");
		assertThat(request.url().toString()).isEqualTo(
				StubExchange.BASE_URL + "/b/api/search?propertyIds=B77120&checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0");
	}

	@Test
	void http200WithFailureCodeIsAFailureNotAnEmptyResult() {
		exchange.respond(HttpStatus.OK, """
				{"resultCode": "E503", "resultMessage": "TEMPORARILY_UNAVAILABLE", "data": null}""");

		Failure<AvailabilityResult> failure = failure(adapter.fetchAvailability(QUERY).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(failure.detail()).isEqualTo("E503 TEMPORARILY_UNAVAILABLE");
		assertThat(failure.elapsed()).isPositive();
	}

	@Test
	void resultCodeMatrix() {
		assertThat(kindOf("E500")).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(kindOf("E429")).isEqualTo(FailureKind.RATE_LIMITED);
		assertThat(kindOf("E401")).isEqualTo(FailureKind.UNAUTHORIZED);
		assertThat(kindOf("E400")).isEqualTo(FailureKind.BAD_REQUEST);
		// 모르는 코드: E5/E4 접두는 관대하게, 그 외는 UNEXPECTED(재시도 안 함)
		assertThat(kindOf("E599")).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(kindOf("E422")).isEqualTo(FailureKind.BAD_REQUEST);
		assertThat(kindOf("E999")).isEqualTo(FailureKind.UNEXPECTED);
		assertThat(kindOf("X1")).isEqualTo(FailureKind.UNEXPECTED);
	}

	@Test
	void unknownCodeDetailSaysItWasClassifiedByPrefix() {
		exchange.respond(HttpStatus.OK, """
				{"resultCode": "E599", "resultMessage": "WEIRD", "data": null}""");

		assertThat(failure(adapter.fetchAvailability(QUERY).block()).detail()).contains("E599 WEIRD", "classified by prefix");
	}

	@Test
	void envelopeWithoutResultCodeIsUnexpected() {
		exchange.respond(HttpStatus.OK, """
				{"data": {"items": []}}""");

		assertThat(failure(adapter.fetchAvailability(QUERY).block()).kind()).isEqualTo(FailureKind.UNEXPECTED);
	}

	@Test
	void successCodeWithNullDataIsUnexpected() {
		exchange.respond(HttpStatus.OK, """
				{"resultCode": "0000", "resultMessage": "SUCCESS", "data": null}""");

		Failure<AvailabilityResult> failure = failure(adapter.fetchAvailability(QUERY).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.UNEXPECTED);
		assertThat(failure.detail()).contains("data is null");
	}

	@Test
	void successBecomesOffersWithTotalPriceAsIsAndNoPriceDetail() {
		exchange.respond(HttpStatus.OK, """
				{"resultCode": "0000", "resultMessage": "SUCCESS", "data": {"items": [{
				  "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
				  "roomId": "R-401", "roomName": "Deluxe Twin Room",
				  "maxOccupancy": 2, "breakfastIncluded": true, "currency": "KRW",
				  "totalPrice": 465000, "taxIncluded": true,
				  "inventory": [{"date": "2026-09-01", "remainingRooms": 3}, {"date": "2026-09-02", "remainingRooms": 1}, {"date": "2026-09-03", "remainingRooms": 5}]
				}]}}""");

		Success<AvailabilityResult> success = (Success<AvailabilityResult>) adapter.fetchAvailability(QUERY).block();

		Offer offer = success.value().offers().get(0);
		assertThat(offer.supplier()).isEqualTo(SupplierBAdapter.ID);
		assertThat(offer.availableRooms()).isEqualTo(1);
		assertThat(offer.price().totalAmount()).isEqualTo(465_000);
		assertThat(offer.price().breakfastIncluded()).isTrue();
		assertThat(offer.price().nights()).isEqualTo(3);
		assertThat(offer.priceDetail()).isNull();
	}

	@Test
	void catalogUnwrapsTheEnvelope() {
		exchange.respond(HttpStatus.OK, """
				{"resultCode": "0000", "resultMessage": "SUCCESS", "data": {"items": [
				  {"propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
				   "rooms": [{"roomId": "R-401", "roomName": "Deluxe Twin Room", "maxOccupancy": 2}]}]}}""");

		Success<List<CatalogProperty>> success = (Success<List<CatalogProperty>>) adapter.fetchCatalog().block();

		assertThat(exchange.onlyRequest().url().getPath()).isEqualTo("/b/api/properties");
		assertThat(success.value()).containsExactly(new CatalogProperty("B77120", "Riverside Hotel Seoul",
				List.of(new CatalogRoomType("R-401", "Deluxe Twin Room", 2))));
	}

	@Test
	void catalogFailureCodeIsAFailure() {
		exchange.respond(HttpStatus.OK, """
				{"resultCode": "E503", "resultMessage": "TEMPORARILY_UNAVAILABLE", "data": null}""");

		assertThat(failure(adapter.fetchCatalog().block()).kind()).isEqualTo(FailureKind.SERVER_ERROR);
	}

	@Test
	void non2xxIsDefendedLikeSupplierA() {
		// 스펙상 오지 않지만 게이트웨이가 낼 수 있다
		exchange.respondWithoutContentType(HttpStatus.SERVICE_UNAVAILABLE, "upstream down");

		Failure<AvailabilityResult> failure = failure(adapter.fetchAvailability(QUERY).block());

		assertThat(failure.kind()).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(failure.detail()).isEqualTo("HTTP 503 upstream down");
	}

	@Test
	void brokenJsonIsUnexpected() {
		exchange.respond(HttpStatus.OK, "{\"resultCode\": ");

		assertThat(failure(adapter.fetchAvailability(QUERY).block()).kind()).isEqualTo(FailureKind.UNEXPECTED);
	}

	private FailureKind kindOf(String resultCode) {
		exchange.respond(HttpStatus.OK, "{\"resultCode\": \"" + resultCode + "\", \"resultMessage\": \"m\", \"data\": null}");
		return failure(adapter.fetchAvailability(QUERY).block()).kind();
	}

	@SuppressWarnings("unchecked")
	private static <T> Failure<T> failure(SupplierResult<T> result) {
		assertThat(result).isInstanceOf(Failure.class);
		return (Failure<T>) result;
	}
}
