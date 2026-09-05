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
import com.demo.stayintegration.catalog.dto.response.SyncReport;
import com.demo.stayintegration.catalog.dto.response.SyncReport.SupplierSyncResult;
import com.demo.stayintegration.catalog.service.CatalogSyncService;
import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.search.dto.response.SearchResponse;
import com.demo.stayintegration.search.dto.response.StayItem;
import com.demo.stayintegration.search.dto.response.SupplierOutcome;
import com.demo.stayintegration.common.SupplierCallMetrics;
import com.demo.stayintegration.search.service.StaySearchService;
import com.demo.stayintegration.supplier.adapter.support.SupplierCallPipelines;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

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
		// 서킷을 두 번의 실패로 열고 300ms 뒤 회복을 본다. 운영값(10회 / 10s)이면 스무 번 호출하고 10초를 기다려야 한다 — 설정을 yaml로 뺀 이유의 절반.
		registry.add("supplier.circuit.minimum-number-of-calls", () -> "2");
		registry.add("supplier.circuit.wait-duration-in-open-state", () -> "300ms");
	}

	private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 1);
	private static final SearchRequest REQUEST = new SearchRequest(CHECK_IN, CHECK_IN.plusDays(3), 2, 0);
	private static final String SEARCH_URL = "/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=%d&children=0";

	@Autowired CatalogSyncService catalogSyncService;
	@Autowired StaySearchService staySearchService;
	@Autowired PropertyMappingRepository propertyMappingRepository;
	@Autowired RoomTypeMappingRepository roomTypeMappingRepository;
	@Autowired MockMvc mvc;
	@Autowired SupplierCallPipelines supplierCallPipelines;
	@Autowired MeterRegistry meterRegistry;
	private final JsonMapper json = new JsonMapper();

	@BeforeEach
	void syncCatalogFromHealthyMock() {
		mock.reset();
		resetCircuits();   // 서킷 상태는 컨텍스트(싱글턴)에 남는다 — 앞 테스트가 열어 둔 서킷이 동기화를 건너뛰게 하면 안 된다
		roomTypeMappingRepository.deleteAll();
		propertyMappingRepository.deleteAll();
		catalogSyncService.sync();
		assertThat(roomTypeMappingRepository.count()).isEqualTo(6);
		resetCircuits();   // 동기화의 성공 호출이 창에 남으면 "실패 2회로 열림"이 흔들린다
	}

	private void resetCircuits() {
		supplierCallPipelines.registry().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
	}

	private CircuitBreaker circuitOf(String supplier) {
		return supplierCallPipelines.registry().circuitBreaker(supplier);
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

	// ── 서킷 브레이커 (07) ─────────────────────────────────────────────────

	private void openCircuitOf(String supplier) {
		mock.setMode(supplier, "availability", MockMode.ERROR);
		for (int i = 0; i < 2; i++) {
			assertThat(supplier(staySearchService.search(REQUEST).block(), supplier).status()).isEqualTo(SupplierOutcome.Status.FAILED);
		}
		assertThat(circuitOf(supplier).getState()).isEqualTo(CircuitBreaker.State.OPEN);
	}

	@Test
	void openCircuitMakesSearchSkipThatSupplierWithoutCallingIt() {
		openCircuitOf("b");
		// 호출했다면 700ms를 기다려 TIMEOUT이 됐을 모드로 바꿔 둔다 — SKIPPED가 즉시 오는 것이 "호출하지 않았다"의 증명이다
		mock.setMode("b", "availability", MockMode.NO_RESPONSE);

		long started = System.nanoTime();
		SearchResponse response = staySearchService.search(REQUEST).block();
		Duration took = Duration.ofNanos(System.nanoTime() - started);

		SupplierOutcome b = supplier(response, "b");
		assertThat(b.status()).isEqualTo(SupplierOutcome.Status.SKIPPED);
		assertThat(b.calls()).isZero();
		assertThat(b.skippedCalls()).isEqualTo(1);
		assertThat(b.failure().kind()).isEqualTo("SKIPPED");
		assertThat(b.failure().detail()).isEqualTo("circuit open");
		assertThat(took).isLessThan(Duration.ofMillis(500));
		assertThat(response.status()).isEqualTo(SearchResponse.Status.OK);   // 보호 동작은 실패가 아니다(06 §3)
		assertThat(response.items()).hasSize(4).allMatch(i -> i.supplier().equals("a"));
		assertThat(response.cacheable()).isTrue();
	}

	@Test
	void circuitRecoversThroughHalfOpenAfterTheWaitDuration() throws Exception {
		openCircuitOf("b");
		mock.reset();
		Thread.sleep(350);   // wait-duration-in-open-state 300ms

		assertThat(supplier(staySearchService.search(REQUEST).block(), "b").status()).isEqualTo(SupplierOutcome.Status.SUCCESS);
		assertThat(circuitOf("b").getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);   // 첫 호출이 반열림으로 넘겼고 아직 판정 전
		assertThat(supplier(staySearchService.search(REQUEST).block(), "b").status()).isEqualTo(SupplierOutcome.Status.SUCCESS);
		assertThat(circuitOf("b").getState()).isEqualTo(CircuitBreaker.State.CLOSED);      // 반열림 최소 호출 수 = min(허용 3, 최소 2) = 2
	}

	@Test
	void openCircuitMakesCatalogSyncSkipThatSupplierAndKeepsItsMappings() {
		openCircuitOf("b");   // 검색 트래픽으로 열렸다 — 서킷은 공급사당 하나라 카탈로그 호출도 막힌다(07 §4)

		SyncReport report = catalogSyncService.sync();

		SupplierSyncResult b = report.suppliers().stream().filter(r -> r.supplier().equals("b")).findFirst().orElseThrow();
		assertThat(b.status()).isEqualTo(SupplierSyncResult.Status.SKIPPED);
		assertThat(b.failure().detail()).isEqualTo("circuit open");
		assertThat(report.suppliers().stream().filter(r -> r.supplier().equals("a")).findFirst().orElseThrow().status())
				.isEqualTo(SupplierSyncResult.Status.SUCCESS);
		assertThat(propertyMappingRepository.count()).isEqualTo(3);   // 기존 매핑 유지(D-8)
		assertThat(roomTypeMappingRepository.count()).isEqualTo(6);
	}

	// ── 관측성 (08) — 레지스트리는 컨텍스트에 누적되므로 델타로 본다 ──────

	private double callCount(String supplier, String outcome) {
		Timer timer = meterRegistry.find(SupplierCallMetrics.CALL).tag("supplier", supplier).tag("api", "availability").tag("outcome", outcome).timer();
		return timer == null ? 0 : timer.count();
	}

	private double counter(String name, String... tags) {
		Counter counter = meterRegistry.find(name).tags(tags).counter();
		return counter == null ? 0 : counter.count();
	}

	@Test
	void metricsAreProducedInTheSameMergePassAsTheResponse() {
		mock.setMode("b", "availability", MockMode.ERROR);
		// A-10023의 객실 하나만 비활성화한다. A-10044처럼 객실이 하나뿐인 숙소를 끄면 숙소 자체가 조회 대상에서 빠져 미매핑이 생기지 않는다
		RoomTypeMapping familySuite = roomTypeMappingRepository.findAllBySupplier("a").stream()
				.filter(r -> r.getSupplierRoomTypeCode().equals("FAM-STE")).findFirst().orElseThrow();
		familySuite.deactivate(Instant.now());
		roomTypeMappingRepository.save(familySuite);
		double aSuccess = callCount("a", "success"), bServerError = callCount("b", "server_error");
		double unmappedA = counter(SupplierCallMetrics.ITEMS_EXCLUDED, "supplier", "a", "reason", "unmapped");
		double partial = counter(SupplierCallMetrics.SEARCH_RESULT, "status", "PARTIAL");

		SearchResponse response = staySearchService.search(REQUEST).block();

		assertThat(response.status()).isEqualTo(SearchResponse.Status.PARTIAL);
		assertThat(supplier(response, "a").unmapped()).isEqualTo(1);
		// 응답에 실린 사실과 같은 값이 지표에 있다 — 같은 순회에서 만들어졌기 때문이다
		assertThat(callCount("a", "success")).isEqualTo(aSuccess + 1);
		assertThat(callCount("b", "server_error")).isEqualTo(bServerError + 1);   // HTTP 200 + E503이 outcome=server_error로
		assertThat(counter(SupplierCallMetrics.ITEMS_EXCLUDED, "supplier", "a", "reason", "unmapped")).isEqualTo(unmappedA + 1);
		assertThat(counter(SupplierCallMetrics.SEARCH_RESULT, "status", "PARTIAL")).isEqualTo(partial + 1);
		// 테스트 컨텍스트가 실제로 기록한다는 증명 — Boot의 지표 export 비활성 커스터마이저(micrometer-metrics-test)가 클래스패스에 없다
		assertThat(callCount("a", "success")).isGreaterThanOrEqualTo(1);
	}

	@Test
	void openCircuitShowsUpAsSkippedCounterAndStateGauge() {
		double skipped = counter(SupplierCallMetrics.CALL_SKIPPED, "supplier", "b", "api", "availability");
		openCircuitOf("b");

		staySearchService.search(REQUEST).block();

		assertThat(counter(SupplierCallMetrics.CALL_SKIPPED, "supplier", "b", "api", "availability")).isEqualTo(skipped + 1);
		assertThat(callCount("b", "skipped")).isZero();   // Skipped는 Timer에 없다
		// Resilience4j 지표가 MeterBinder 빈으로 묶여 있고, 태그 name이 공급사 id다
		Gauge open = meterRegistry.find("resilience4j.circuitbreaker.state").tag("name", "b").tag("state", "open").gauge();
		assertThat(open).isNotNull();
		assertThat(open.value()).isEqualTo(1.0);
	}

	@Test
	void actuatorExposesHealthAndMetricsButNotEnv() throws Exception {
		staySearchService.search(REQUEST).block();   // supplier.call이 최소 한 번은 기록되어 있게

		MvcResult metrics = mvc.perform(get("/actuator/metrics/supplier.call?tag=supplier:a&tag=outcome:success")).andReturn();
		assertThat(metrics.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = json.readTree(metrics.getResponse().getContentAsString());
		assertThat(body.path("name").asString()).isEqualTo("supplier.call");
		assertThat(body.path("measurements")).isNotEmpty();

		MvcResult health = mvc.perform(get("/actuator/health")).andReturn();
		assertThat(health.getResponse().getStatus()).isEqualTo(200);
		assertThat(json.readTree(health.getResponse().getContentAsString()).path("status").asString()).isEqualTo("UP");

		assertThat(mvc.perform(get("/actuator/env")).andReturn().getResponse().getStatus()).isEqualTo(404);   // API 키가 보이는 곳은 열지 않는다
	}

	// ── API 문서 (springdoc 3.x) — 계약이 코드에서 자동으로 나오는지 ────────

	@Test
	void openApiDocumentDescribesTheSearchContractFromTheCode() throws Exception {
		MvcResult docs = mvc.perform(get("/v3/api-docs")).andReturn();
		assertThat(docs.getResponse().getStatus()).isEqualTo(200);
		JsonNode api = json.readTree(docs.getResponse().getContentAsString());
		JsonNode search = api.path("paths").path("/api/v1/stays/search").path("get");
		assertThat(search.isMissingNode()).isFalse();
		assertThat(search.path("parameters")).extracting(p -> p.path("name").asString())
				.containsExactlyInAnyOrder("checkIn", "checkOut", "adults", "children");
		assertThat(search.path("parameters")).allSatisfy(p -> assertThat(p.path("required").asBoolean()).isTrue());
		// 응답 record가 스키마로 잡힌다 — 포트 record가 아니라 응답 DTO(PriceResponse)가 계약이다
		assertThat(api.path("components").path("schemas").has("SearchResponse")).isTrue();
		assertThat(api.path("components").path("schemas").has("PriceResponse")).isTrue();
		assertThat(api.path("paths").has("/admin/catalog/sync")).isTrue();
		assertThat(api.path("paths").has("/actuator/health")).isFalse();   // 관리 지표 엔드포인트는 문서에 섞지 않는다

		assertThat(mvc.perform(get("/swagger-ui/index.html")).andReturn().getResponse().getStatus()).isEqualTo(200);
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
