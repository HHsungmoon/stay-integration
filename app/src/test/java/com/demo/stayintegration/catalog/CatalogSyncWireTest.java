package com.demo.stayintegration.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.demo.mocksupplier.control.MockMode;
import com.demo.stayintegration.MockSupplierServer;
import com.demo.stayintegration.TestcontainersConfiguration;
import com.demo.stayintegration.catalog.dto.response.SyncOutcome;
import com.demo.stayintegration.catalog.dto.response.SyncReport;
import com.demo.stayintegration.catalog.dto.response.SyncReport.SupplierSyncResult;
import com.demo.stayintegration.catalog.dto.response.SyncReport.SupplierSyncResult.Status;
import com.demo.stayintegration.catalog.repository.PropertyMappingRepository;
import com.demo.stayintegration.catalog.repository.RoomTypeMappingRepository;
import com.demo.stayintegration.catalog.service.CatalogSyncService;

// 처음으로 전 구간이 실제로 이어진다: Boot가 자동설정한 WebClient.Builder → 실제 어댑터 → Mock HTTP → 정규화 → JPA 저장.
// 여기서 컨텍스트가 뜬다는 것 자체가 §6.1(starter-webclient가 WebClient.Builder 빈을 준다)의 증명이다.
@SpringBootTest(properties = { "catalog.sync-on-startup=false" })
@Import(TestcontainersConfiguration.class)
class CatalogSyncWireTest {

	static final MockSupplierServer mock = MockSupplierServer.start();

	@DynamicPropertySource
	static void pointSuppliersAtTheMock(DynamicPropertyRegistry registry) {
		for (String supplier : new String[] { "a", "b" }) {
			registry.add("supplier.endpoints." + supplier + ".base-url", mock::baseUrl);
			// 무응답 케이스를 2초 기다리지 않기 위해. 데드라인(3s)보다는 짧아야 TIMEOUT이 어댑터에서 판정된다.
			registry.add("supplier.endpoints." + supplier + ".response-timeout", () -> "700ms");
		}
	}

	@Autowired CatalogSyncService catalogSyncService;
	@Autowired PropertyMappingRepository propertyMappingRepository;
	@Autowired RoomTypeMappingRepository roomTypeMappingRepository;

	@BeforeEach
	void cleanDatabase() {
		roomTypeMappingRepository.deleteAll();
		propertyMappingRepository.deleteAll();
	}

	@AfterEach
	void resetMock() {
		mock.reset();
	}

	@Test
	void syncFillsTheDatabaseFromRealMockResponses() {
		SyncReport report = catalogSyncService.sync();

		assertThat(result(report, "a").status()).isEqualTo(Status.SUCCESS);
		assertThat(result(report, "a").outcome()).isEqualTo(new SyncOutcome(2 + 4, 0, 0, 0));
		assertThat(result(report, "b").status()).isEqualTo(Status.SUCCESS);
		assertThat(result(report, "b").outcome()).isEqualTo(new SyncOutcome(1 + 2, 0, 0, 0));
		assertThat(propertyMappingRepository.count()).isEqualTo(3);
		assertThat(roomTypeMappingRepository.count()).isEqualTo(6);
		assertThat(propertyMappingRepository.findAllBySupplier("b")).singleElement()
				.satisfies(mapping -> assertThat(mapping.getSupplierHotelName()).isEqualTo("Riverside Hotel Seoul"));
	}

	@Test
	void http200FailureOfSupplierBIsReportedAsFailureWhileAIsSynced() {
		mock.setMode("b", "catalog", MockMode.ERROR);

		SyncReport report = catalogSyncService.sync();

		SupplierSyncResult b = result(report, "b");
		assertThat(b.status()).isEqualTo(Status.FAILED);
		assertThat(b.failure().kind()).isEqualTo("SERVER_ERROR");
		assertThat(b.failure().detail()).isEqualTo("E503 TEMPORARILY_UNAVAILABLE");
		assertThat(result(report, "a").status()).isEqualTo(Status.SUCCESS);
		assertThat(propertyMappingRepository.findAllBySupplier("a")).hasSize(2);
		assertThat(propertyMappingRepository.findAllBySupplier("b")).isEmpty();
	}

	@Test
	void noResponseOfSupplierAIsTimeoutAndDoesNotDelayB() {
		mock.setMode("a", "catalog", MockMode.NO_RESPONSE);

		SyncReport report = catalogSyncService.sync();

		assertThat(result(report, "a").status()).isEqualTo(Status.FAILED);
		assertThat(result(report, "a").failure().kind()).isEqualTo("TIMEOUT");
		assertThat(result(report, "b").status()).isEqualTo(Status.SUCCESS);
		assertThat(report.durationMs()).isLessThan(3_000);
	}

	private static SupplierSyncResult result(SyncReport report, String supplier) {
		return report.suppliers().stream().filter(s -> s.supplier().equals(supplier)).findFirst().orElseThrow();
	}
}
