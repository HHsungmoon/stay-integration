package com.demo.stayintegration.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.demo.mocksupplier.control.MockMode;
import com.demo.stayintegration.MockSupplierServer;
import com.demo.stayintegration.TestcontainersConfiguration;
import com.demo.stayintegration.catalog.entity.PropertyMapping;
import com.demo.stayintegration.catalog.entity.RoomTypeMapping;
import com.demo.stayintegration.catalog.repository.PropertyMappingRepository;
import com.demo.stayintegration.catalog.repository.RoomTypeMappingRepository;
import com.demo.stayintegration.catalog.service.CatalogSyncService;
import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.search.dto.response.SearchResponse;
import com.demo.stayintegration.search.dto.response.StayItem;
import com.demo.stayintegration.search.dto.response.SupplierOutcome;
import com.demo.stayintegration.search.service.StaySearchService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

// 견고성 통합 테스트 첫 판(CLAUDE.md "버리지 않는 것"). 동기화 → 검색 전 구간이 실제 HTTP로 이어지고,
// Mock 모드를 바꿔가며 (1) 정상 병합 (2) 한쪽 무응답 (3) 본문 코드 실패 (4) 전부 실패 (5) 미매핑을 본다. 서킷(Skipped)은 7단계에서 여기 추가한다.
@SpringBootTest(properties = { "catalog.sync-on-startup=false" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class StaySearchWireTest {

	static final MockSupplierServer mock = MockSupplierServer.start();

	@DynamicPropertySource
	static void pointSuppliersAtTheMock(DynamicPropertyRegistry registry) {
		for (String supplier : new String[] { "a", "b" }) {
			registry.add("supplier.endpoints." + supplier + ".base-url", mock::baseUrl);
			// 무응답 케이스를 2초 기다리지 않기 위해. 데드라인(3s)보다는 짧아야 TIMEOUT이 어댑터에서 판정된다.
			registry.add("supplier.endpoints." + supplier + ".response-timeout", () -> "700ms");
		}
	}

	private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 1);
	private static final SearchRequest REQUEST = new SearchRequest(CHECK_IN, CHECK_IN.plusDays(3), 2, 0);
	private static final String SEARCH_URL = "/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=%d&children=0";

	@Autowired CatalogSyncService catalogSyncService;
	@Autowired StaySearchService staySearchService;
	@Autowired PropertyMappingRepository propertyMappingRepository;
	@Autowired RoomTypeMappingRepository roomTypeMappingRepository;
	@Autowired MockMvc mvc;
	private final JsonMapper json = new JsonMapper();

	@BeforeEach
	void syncCatalogFromHealthyMock() {
		mock.reset();
		roomTypeMappingRepository.deleteAll();
		propertyMappingRepository.deleteAll();
		catalogSyncService.sync();
		assertThat(roomTypeMappingRepository.count()).isEqualTo(6);
	}

	@AfterEach
	void resetMock() {
		mock.reset();
	}

	@Test
	void healthySuppliersMergeIntoOneOkResponseWithInternalIds() {
		SearchResponse response = staySearchService.search(REQUEST).block();

		assertThat(response.status()).isEqualTo(SearchResponse.Status.OK);
		assertThat(response.cacheable()).isTrue();
		assertThat(response.items()).hasSize(6);
		assertThat(supplier(response, "a").status()).isEqualTo(SupplierOutcome.Status.SUCCESS);
		assertThat(supplier(response, "a").offers()).isEqualTo(4);
		assertThat(supplier(response, "b").offers()).isEqualTo(2);

		// A: 날짜별 (net + tax) 합으로 gross 상향, 재고는 기간 내 최솟값(3,1,5 → 1)
		StayItem deluxeTwinFromA = item(response, "a", "Deluxe Twin");
		assertThat(deluxeTwinFromA.price().totalAmount()).isEqualTo(429_000);
		assertThat(deluxeTwinFromA.price().taxIncluded()).isTrue();
		assertThat(deluxeTwinFromA.price().nights()).isEqualTo(3);
		assertThat(deluxeTwinFromA.availableRooms()).isEqualTo(1);
		assertThat(deluxeTwinFromA.priceDetail().taxAmount()).isEqualTo(39_000);
		assertThat(deluxeTwinFromA.priceDetail().nightlyBreakdown()).hasSize(3);

		// B: 총액 그대로, priceDetail 없음, 조식 포함
		StayItem deluxeTwinFromB = item(response, "b", "Deluxe Twin Room");
		assertThat(deluxeTwinFromB.price().totalAmount()).isEqualTo(465_000);
		assertThat(deluxeTwinFromB.price().breakfastIncluded()).isTrue();
		assertThat(deluxeTwinFromB.priceDetail()).isNull();

		// 같은 호텔이지만 내부 숙소는 둘이다 — 공통 키가 없다
		assertThat(deluxeTwinFromA.propertyId()).isNotEqualTo(deluxeTwinFromB.propertyId());
		Map<String, PropertyMapping> propertiesOfA = propertyMappingRepository.findAllBySupplier("a").stream()
				.collect(Collectors.toMap(PropertyMapping::getSupplierHotelCode, Function.identity()));
		assertThat(deluxeTwinFromA.propertyId()).isEqualTo(propertiesOfA.get("A-10023").getId());

		// D-6: 하루라도 0이면(2,0,4) 재고 0으로 노출, 응답에서 빼지 않는다
		StayItem namsanStandard = response.items().stream()
				.filter(i -> i.propertyId() == propertiesOfA.get("A-10044").getId()).findFirst().orElseThrow();
		assertThat(namsanStandard.availableRooms()).isZero();
		assertThat(namsanStandard.available()).isFalse();
	}

	@Test
	void supplierANotRespondingIsTimeoutWhileBStillAnswersWithinTheDeadline() {
		mock.setMode("a", "availability", MockMode.NO_RESPONSE);

		long started = System.nanoTime();
		SearchResponse response = staySearchService.search(REQUEST).block();
		Duration took = Duration.ofNanos(System.nanoTime() - started);

		assertThat(response.status()).isEqualTo(SearchResponse.Status.PARTIAL);
		SupplierOutcome a = supplier(response, "a");
		assertThat(a.status()).isEqualTo(SupplierOutcome.Status.FAILED);
		assertThat(a.failure().kind()).isEqualTo("TIMEOUT");
		assertThat(a.failure().retryable()).isTrue();
		assertThat(supplier(response, "b").status()).isEqualTo(SupplierOutcome.Status.SUCCESS);
		assertThat(response.items()).hasSize(2).allMatch(i -> i.supplier().equals("b"));
		assertThat(took).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void supplierBHttp200ErrorBodyIsRecognizedAsFailureNotAsEmptyResult() {
		mock.setMode("b", "availability", MockMode.ERROR);

		SearchResponse response = staySearchService.search(REQUEST).block();

		assertThat(response.status()).isEqualTo(SearchResponse.Status.PARTIAL);
		SupplierOutcome b = supplier(response, "b");
		assertThat(b.status()).isEqualTo(SupplierOutcome.Status.FAILED);
		assertThat(b.failure().kind()).isEqualTo("SERVER_ERROR");
		assertThat(b.failure().detail()).isEqualTo("E503 TEMPORARILY_UNAVAILABLE");
		assertThat(response.items()).hasSize(4).allMatch(i -> i.supplier().equals("a"));
	}

	@Test
	void everySupplierFailingIsHttp200AllFailedWithNoStore() throws Exception {
		mock.setMode("a", "availability", MockMode.ERROR);
		mock.setMode("b", "availability", MockMode.ERROR);

		MvcResult result = mvc.perform(get(SEARCH_URL.formatted(2))).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		JsonNode body = json.readTree(result.getResponse().getContentAsString());
		assertThat(body.path("status").asString()).isEqualTo("ALL_FAILED");
		assertThat(body.path("items")).isEmpty();
		assertThat(body.path("suppliers")).hasSize(2).allSatisfy(s -> assertThat(s.path("status").asString()).isEqualTo("FAILED"));
		// A는 HTTP 상태로, B는 본문 코드로 알렸지만 같은 실패 값으로 번역된다
		assertThat(body.path("suppliers").get(0).path("failure").path("kind").asString()).isEqualTo("SERVER_ERROR");
		assertThat(body.path("suppliers").get(0).path("failure").path("detail").asString()).contains("503");
		assertThat(body.path("suppliers").get(1).path("failure").path("kind").asString()).isEqualTo("SERVER_ERROR");
	}

	@Test
	void emptyResultIsOkButNotCacheable() throws Exception {
		// 인원 5명을 수용하는 객실은 Mock에 없다 — 공급사가 빈 결과를 정상 응답한다
		MvcResult result = mvc.perform(get(SEARCH_URL.formatted(5))).andReturn();

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		JsonNode body = json.readTree(result.getResponse().getContentAsString());
		assertThat(body.path("status").asString()).isEqualTo("OK");
		assertThat(body.path("items")).isEmpty();
	}

	@Test
	void roomTypeDeactivatedAfterSyncIsCountedAsUnmappedNotAsFailure() {
		RoomTypeMapping familyRoom = roomTypeMappingRepository.findAllBySupplier("b").stream()
				.filter(r -> r.getSupplierRoomTypeCode().equals("R-402")).findFirst().orElseThrow();
		familyRoom.deactivate(Instant.now());
		roomTypeMappingRepository.save(familyRoom);

		SearchResponse response = staySearchService.search(REQUEST).block();

		assertThat(response.status()).isEqualTo(SearchResponse.Status.OK);
		SupplierOutcome b = supplier(response, "b");
		assertThat(b.status()).isEqualTo(SupplierOutcome.Status.SUCCESS);
		assertThat(b.offers()).isEqualTo(1);
		assertThat(b.unmapped()).isEqualTo(1);   // 동기화가 밀렸다는 신호
		assertThat(response.items()).hasSize(5);
	}

	private static SupplierOutcome supplier(SearchResponse response, String supplier) {
		return response.suppliers().stream().filter(s -> s.supplier().equals(supplier)).findFirst().orElseThrow();
	}

	private static StayItem item(SearchResponse response, String supplier, String roomTypeName) {
		return response.items().stream()
				.filter(i -> i.supplier().equals(supplier) && i.roomTypeName().equals(roomTypeName))
				.findFirst().orElseThrow();
	}
}
