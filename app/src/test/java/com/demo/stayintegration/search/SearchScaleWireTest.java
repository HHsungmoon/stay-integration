package com.demo.stayintegration.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.demo.stayintegration.MockSupplierServer;
import com.demo.stayintegration.TestcontainersConfiguration;
import com.demo.stayintegration.catalog.dto.response.SyncReport;
import com.demo.stayintegration.catalog.repository.PropertyMappingRepository;
import com.demo.stayintegration.catalog.repository.RoomTypeMappingRepository;
import com.demo.stayintegration.catalog.service.CatalogSyncService;
import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.search.dto.response.SearchResponse;
import com.demo.stayintegration.search.dto.response.SupplierOutcome;
import com.demo.stayintegration.search.service.StaySearchService;
import com.demo.stayintegration.supplier.adapter.support.SupplierCallPipelines;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

// 50개 청크·동시성 4·데드라인이 실제 HTTP로 흐르는지. 부록 예시는 숙소 3개라 청크가 늘 1개였고, README가 적어 둔
// "2,000숙소 = 40청크 …" 산술은 이 테스트 전까지 한 번도 돌아간 적이 없었다. Mock에 합성 숙소를 붙여 그 경로를 지나간다.
// 데드라인을 1.5s로 줄인 이유: 두 파동(동시 4 × 지연 1s)이 데드라인을 넘는 상황을 3초 기다리지 않고 만들기 위해. 응답 타임아웃은 기본 2s 그대로 —
// 청크 하나가 자기 타임아웃에 걸리는 게 아니라 데드라인에 잘리는 것을 봐야 한다.
@SpringBootTest(properties = { "catalog.sync-on-startup=false", "search.deadline=1500ms" })
@Import(TestcontainersConfiguration.class)
class SearchScaleWireTest {

	static final MockSupplierServer mock = MockSupplierServer.start();

	@DynamicPropertySource
	static void pointSuppliersAtTheMock(DynamicPropertyRegistry registry) {
		for (String supplier : new String[] { "a", "b" }) {
			registry.add("supplier.endpoints." + supplier + ".base-url", mock::baseUrl);
		}
	}

	private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 1);
	private static final SearchRequest REQUEST = new SearchRequest(CHECK_IN, CHECK_IN.plusDays(3), 2, 0);
	private static final int BASE_HOTELS_A = 2, BASE_ROOMS_A = 4, BASE_HOTELS_B = 1, BASE_ROOMS_B = 2;

	@Autowired CatalogSyncService catalogSyncService;
	@Autowired StaySearchService staySearchService;
	@Autowired PropertyMappingRepository propertyMappingRepository;
	@Autowired RoomTypeMappingRepository roomTypeMappingRepository;
	@Autowired SupplierCallPipelines supplierCallPipelines;

	@BeforeEach
	void cleanSlate() {
		mock.reset();
		supplierCallPipelines.registry().getAllCircuitBreakers().forEach(CircuitBreaker::reset);
		roomTypeMappingRepository.deleteAll();
		propertyMappingRepository.deleteAll();
	}

	@AfterEach
	void resetMock() {
		mock.reset();
	}

	private void syncWithSyntheticHotels(int count) {
		mock.syntheticHotels(count);
		SyncReport report = catalogSyncService.sync();
		assertThat(report.suppliers()).allSatisfy(r -> assertThat(r.status()).isEqualTo(SyncReport.SupplierSyncResult.Status.SUCCESS));
		assertThat(propertyMappingRepository.count()).isEqualTo(BASE_HOTELS_A + count + BASE_HOTELS_B + count);
		assertThat(roomTypeMappingRepository.count()).isEqualTo(BASE_ROOMS_A + count + BASE_ROOMS_B + count);
		supplierCallPipelines.registry().getAllCircuitBreakers().forEach(CircuitBreaker::reset);   // 동기화 호출은 검색 통계에서 빼 둔다
	}

	@Test
	void hotelsBeyondFiftyAreSplitIntoChunksAndEveryChunkShowsUpInTheResponse() {
		syncWithSyntheticHotels(120);   // A 122코드 → 50·50·22, B 121코드 → 50·50·21

		long started = System.nanoTime();
		SearchResponse response = staySearchService.search(REQUEST).block();
		Duration took = Duration.ofNanos(System.nanoTime() - started);

		assertThat(response.status()).isEqualTo(SearchResponse.Status.OK);
		SupplierOutcome a = supplier(response, "a");
		SupplierOutcome b = supplier(response, "b");
		assertThat(a.calls()).isEqualTo(3);
		assertThat(a.failedCalls()).isZero();
		assertThat(a.offers()).isEqualTo(BASE_ROOMS_A + 120);   // 예시 객실 4 + 합성 120 (모두 2인 수용)
		assertThat(b.calls()).isEqualTo(3);
		assertThat(b.offers()).isEqualTo(BASE_ROOMS_B + 120);
		assertThat(response.items()).hasSize(BASE_ROOMS_A + BASE_ROOMS_B + 240);
		assertThat(took).isLessThan(Duration.ofMillis(1500));
		System.out.println("scale: 120 synthetic hotels per supplier -> 3 chunks each, search took " + took.toMillis() + "ms");
	}

	@Test
	void whenWavesExceedTheDeadlineChunksThatArrivedSurviveAndTheRestAreTimeouts() {
		syncWithSyntheticHotels(298);   // A 300코드 → 청크 6, 동시 4 → 파동 2. 파동마다 1s면 두 번째 파동은 데드라인 1.5s 밖이다
		mock.delay("a", "availability", 1_000);

		long started = System.nanoTime();
		SearchResponse response = staySearchService.search(REQUEST).block();
		Duration took = Duration.ofNanos(System.nanoTime() - started);

		SupplierOutcome a = supplier(response, "a");
		assertThat(a.status()).isEqualTo(SupplierOutcome.Status.PARTIAL);
		assertThat(a.calls()).isEqualTo(6);              // 청크 수 = 결과 수. 안 온 청크는 TIMEOUT으로 채워진다
		assertThat(a.failedCalls()).isEqualTo(2);        // 두 번째 파동의 두 청크
		assertThat(a.failure().kind()).isEqualTo("TIMEOUT");
		assertThat(a.failure().detail()).contains("deadline");
		// 첫 파동 4청크 = 200코드. 예시 숙소 2개(객실 4)가 어느 청크에 있느냐에 따라 200 또는 202
		assertThat(a.offers()).isBetween(200, 202);

		SupplierOutcome b = supplier(response, "b");    // B는 지연이 없어 6청크 전부 온다
		assertThat(b.status()).isEqualTo(SupplierOutcome.Status.SUCCESS);
		assertThat(b.calls()).isEqualTo(6);
		assertThat(b.offers()).isEqualTo(BASE_ROOMS_B + 298);

		assertThat(response.status()).isEqualTo(SearchResponse.Status.PARTIAL);
		assertThat(took).isGreaterThanOrEqualTo(Duration.ofMillis(1500)).isLessThan(Duration.ofMillis(2300));   // 데드라인에서 잘렸고 2초 파동을 기다리지 않았다
		System.out.println("scale: 298 synthetic hotels, A delayed 1s -> 6 chunks in 2 waves, deadline 1.5s, took " + took.toMillis() + "ms, A offers " + a.offers());
	}

	private static SupplierOutcome supplier(SearchResponse response, String supplier) {
		return response.suppliers().stream().filter(s -> s.supplier().equals(supplier)).findFirst().orElseThrow();
	}
}
