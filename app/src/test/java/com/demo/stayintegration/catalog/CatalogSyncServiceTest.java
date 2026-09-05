package com.demo.stayintegration.catalog;

import com.demo.stayintegration.catalog.dto.response.SyncReport;
import com.demo.stayintegration.catalog.dto.response.SyncOutcome;
import com.demo.stayintegration.catalog.entity.PropertyMapping;
import com.demo.stayintegration.catalog.entity.RoomTypeMapping;
import com.demo.stayintegration.catalog.repository.PropertyMappingRepository;
import com.demo.stayintegration.catalog.repository.RoomTypeMappingRepository;
import com.demo.stayintegration.catalog.service.CatalogSyncService;
import com.demo.stayintegration.catalog.service.SyncAlreadyRunningException;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.demo.stayintegration.TestcontainersConfiguration;
import com.demo.stayintegration.catalog.dto.response.SyncReport.SupplierSyncResult;
import com.demo.stayintegration.catalog.dto.response.SyncReport.SupplierSyncResult.Status;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.CatalogRoomType;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierAdapter;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

import reactor.core.publisher.Mono;

// 포트를 직접 구현한 stub으로 catalog의 책임만 검증한다. 어댑터가 들어와도 이 테스트는 바뀌지 않아야 한다.
// 데드라인을 1s로 줄인 이유: 초과 케이스를 3초 기다리지 않기 위해.
@SpringBootTest(properties = { "catalog.sync-on-startup=false", "catalog.sync-deadline=1s" })
@Import({ TestcontainersConfiguration.class, CatalogSyncServiceTest.StubAdapters.class })
class CatalogSyncServiceTest {

	@TestConfiguration
	static class StubAdapters {
		@Bean StubAdapter supplierA() { return new StubAdapter("A"); }
		@Bean StubAdapter supplierB() { return new StubAdapter("B"); }
	}

	static class StubAdapter implements SupplierAdapter {
		private final SupplierId id;
		private volatile Supplier<SupplierResult<List<CatalogProperty>>> next;
		private volatile Duration delay = Duration.ZERO;

		StubAdapter(String id) { this.id = new SupplierId(id); }

		@Override public SupplierId id() { return id; }

		@Override
		public Mono<SupplierResult<List<CatalogProperty>>> fetchCatalog() {
			Mono<SupplierResult<List<CatalogProperty>>> mono = Mono.fromSupplier(next);
			return delay.isZero() ? mono : mono.delayElement(delay);
		}

		void returns(CatalogProperty... properties) {
			List<CatalogProperty> list = List.of(properties);
			next = () -> new SupplierResult.Success<>(id, list, Duration.ofMillis(5));
		}
		void fails(FailureKind kind, String detail) { next = () -> new SupplierResult.Failure<>(id, kind, detail, Duration.ofMillis(5)); }
		void skips(String reason)                   { next = () -> new SupplierResult.Skipped<>(id, reason, Duration.ZERO); }
		void throwsException()                      { next = () -> { throw new IllegalStateException("boom"); }; }
		void respondsAfter(Duration d)              { delay = d; }
	}

	static CatalogRoomType room(String code, String name, int max) { return new CatalogRoomType(code, name, max); }
	static CatalogProperty property(String code, String name, CatalogRoomType... rooms) { return new CatalogProperty(code, name, List.of(rooms)); }

	static final CatalogProperty RIVERSIDE = property("A-10023", "Riverside Hotel Seoul",
			room("DLX-TWN", "Deluxe Twin", 2), room("STD-DBL", "Standard Double", 2), room("FAM-STE", "Family Suite", 4));
	static final CatalogProperty NAMSAN = property("A-10044", "Namsan Garden Stay", room("STD-DBL", "Standard Double", 2));
	static final CatalogProperty B_RIVERSIDE = property("B77120", "Riverside Hotel Seoul",
			room("R-401", "Deluxe Twin Room", 2), room("R-402", "Family Room", 4));

	@Autowired CatalogSyncService catalogSyncService;
	@Autowired PropertyMappingRepository propertyMappingRepository;
	@Autowired RoomTypeMappingRepository roomTypeMappingRepository;
	@Autowired @Qualifier("supplierA") StubAdapter supplierA;
	@Autowired @Qualifier("supplierB") StubAdapter supplierB;

	@BeforeEach
	void resetDatabaseAndStubs() {
		roomTypeMappingRepository.deleteAll();
		propertyMappingRepository.deleteAll();
		supplierA.returns(RIVERSIDE, NAMSAN);
		supplierA.respondsAfter(Duration.ZERO);
		supplierB.returns(B_RIVERSIDE);
		supplierB.respondsAfter(Duration.ZERO);
	}

	// ── 멱등성 ────────────────────────────────────────────────────────────

	@Test
	void firstSyncCreatesEverything() {
		SyncReport report = catalogSyncService.sync();

		assertThat(result(report, "A").status()).isEqualTo(Status.SUCCESS);
		assertThat(result(report, "A").outcome()).isEqualTo(new SyncOutcome(2 + 4, 0, 0, 0));   // 숙소 2 + 객실 4
		assertThat(result(report, "B").outcome()).isEqualTo(new SyncOutcome(1 + 2, 0, 0, 0));
		assertThat(propertyMappingRepository.count()).isEqualTo(3);
		assertThat(roomTypeMappingRepository.count()).isEqualTo(6);
		assertThat(roomTypeMappingRepository.findAll()).allMatch(RoomTypeMapping::isActive);
	}

	@Test
	void syncingAgainWithSameInputKeepsIdsAndRowCountsAndCreatesNothing() {
		catalogSyncService.sync();
		Map<String, Long> propertyIds = propertyIdsOf("A");
		Map<String, Long> roomTypeIds = roomTypeIdsOf("A");

		SyncReport second = catalogSyncService.sync();
		SyncReport third = catalogSyncService.sync();

		for (SyncReport r : List.of(second, third)) {
			assertThat(result(r, "A").outcome().created()).isZero();
			assertThat(result(r, "A").outcome().reactivated()).isZero();
			assertThat(result(r, "A").outcome().deactivated()).isZero();
		}
		assertThat(propertyIdsOf("A")).isEqualTo(propertyIds);
		assertThat(roomTypeIdsOf("A")).isEqualTo(roomTypeIds);
		assertThat(propertyMappingRepository.count()).isEqualTo(3);
		assertThat(roomTypeMappingRepository.count()).isEqualTo(6);
	}

	@Test
	void disappearedPropertyIsDeactivatedAndReappearsWithTheSameId() {
		catalogSyncService.sync();
		long namsanId = propertyIdsOf("A").get("A-10044");
		long namsanRoomId = roomTypeIdsOf("A").get("A-10044|STD-DBL");

		// 2회차: 남산이 목록에서 사라짐
		supplierA.returns(RIVERSIDE);
		SyncReport second = catalogSyncService.sync();

		assertThat(result(second, "A").outcome().deactivated()).isEqualTo(2);   // 숙소 1 + 객실 1
		assertThat(propertyMappingRepository.count()).isEqualTo(3);                             // 행은 남는다
		PropertyMapping namsan = propertyMappingRepository.findById(namsanId).orElseThrow();
		assertThat(namsan.isActive()).isFalse();
		assertThat(roomTypeMappingRepository.findById(namsanRoomId).orElseThrow().isActive()).isFalse();

		// 3회차: 다시 등장 — 같은 ID로 살아나야 한다. 하드 삭제했다면 여기서 새 ID가 나온다.
		supplierA.returns(RIVERSIDE, NAMSAN);
		SyncReport third = catalogSyncService.sync();

		assertThat(result(third, "A").outcome().created()).isZero();
		assertThat(result(third, "A").outcome().reactivated()).isEqualTo(2);
		assertThat(propertyIdsOf("A").get("A-10044")).isEqualTo(namsanId);
		assertThat(roomTypeIdsOf("A").get("A-10044|STD-DBL")).isEqualTo(namsanRoomId);
		assertThat(propertyMappingRepository.findById(namsanId).orElseThrow().isActive()).isTrue();
	}

	@Test
	void renamedPropertyAndRoomTypeUpdateSnapshotsButKeepIds() {
		catalogSyncService.sync();
		long id = propertyIdsOf("A").get("A-10023");

		supplierA.returns(property("A-10023", "Riverside Hotel Seoul (Renovated)", room("DLX-TWN", "Deluxe Twin Renewed", 3),
				room("STD-DBL", "Standard Double", 2), room("FAM-STE", "Family Suite", 4)), NAMSAN);
		catalogSyncService.sync();

		PropertyMapping riverside = propertyMappingRepository.findById(id).orElseThrow();
		assertThat(riverside.getSupplierHotelName()).isEqualTo("Riverside Hotel Seoul (Renovated)");
		RoomTypeMapping dlx = roomTypeMappingRepository.findAllBySupplier("A").stream()
				.filter(r -> r.getSupplierRoomTypeCode().equals("DLX-TWN")).findFirst().orElseThrow();
		assertThat(dlx.getSupplierRoomTypeName()).isEqualTo("Deluxe Twin Renewed");
		assertThat(dlx.getMaxOccupancy()).isEqualTo(3);
	}

	@Test
	void duplicateCodesFromSupplierAreIgnoredInsteadOfFailingTheWholeSync() {
		supplierA.returns(RIVERSIDE, RIVERSIDE);   // 같은 숙소를 두 번

		SyncReport report = catalogSyncService.sync();

		assertThat(result(report, "A").status()).isEqualTo(Status.SUCCESS);
		assertThat(propertyMappingRepository.findAllBySupplier("A")).hasSize(1);
	}

	// ── D-8: 공급사 하나의 실패가 다른 공급사를 막지 않는다 ─────────────────

	@Test
	void failedSupplierKeepsExistingMappingsWhileTheOtherIsUpdated() {
		catalogSyncService.sync();
		Map<String, Long> aBefore = propertyIdsOf("A");

		supplierA.fails(FailureKind.TRANSIENT, "503 SERVICE_UNAVAILABLE");
		supplierB.returns(B_RIVERSIDE, property("B99999", "New Property", room("R-1", "Room", 2)));
		SyncReport report = catalogSyncService.sync();

		SupplierSyncResult a = result(report, "A");
		assertThat(a.status()).isEqualTo(Status.FAILED);
		assertThat(a.failure().kind()).isEqualTo("TRANSIENT");
		assertThat(a.failure().detail()).contains("503");
		assertThat(propertyIdsOf("A")).isEqualTo(aBefore);                       // A는 그대로
		assertThat(propertyMappingRepository.findAllBySupplier("A")).allMatch(PropertyMapping::isActive);

		assertThat(result(report, "B").status()).isEqualTo(Status.SUCCESS);
		assertThat(propertyMappingRepository.findAllBySupplier("B")).hasSize(2);               // B는 갱신됨
	}

	@Test
	void skippedSupplierIsReportedAndLeavesMappingsUntouched() {
		catalogSyncService.sync();
		supplierB.skips("circuit open");

		SyncReport report = catalogSyncService.sync();

		assertThat(result(report, "B").status()).isEqualTo(Status.SKIPPED);
		assertThat(result(report, "B").failure().detail()).isEqualTo("circuit open");
		assertThat(propertyMappingRepository.findAllBySupplier("B")).hasSize(1);
	}

	@Test
	void adapterThrowingIsContainedAsFailureOfThatSupplierOnly() {
		supplierA.throwsException();

		SyncReport report = catalogSyncService.sync();

		assertThat(result(report, "A").status()).isEqualTo(Status.FAILED);
		assertThat(result(report, "A").failure().detail()).contains("IllegalStateException");
		assertThat(result(report, "B").status()).isEqualTo(Status.SUCCESS);
	}

	@Test
	void supplierExceedingDeadlineIsFailedWithoutLosingTheOthersResult() {
		supplierA.respondsAfter(Duration.ofMillis(1_500));   // 데드라인 1s

		SyncReport report = catalogSyncService.sync();

		assertThat(result(report, "A").status()).isEqualTo(Status.FAILED);
		assertThat(result(report, "A").failure().detail()).contains("exceeded");
		assertThat(result(report, "B").status()).isEqualTo(Status.SUCCESS);   // B의 결과는 살아 있다
	}

	// ── 동시 실행 ─────────────────────────────────────────────────────────

	@Test
	void concurrentSyncIsRejectedWhileOneIsRunning() throws Exception {
		supplierA.respondsAfter(Duration.ofMillis(600));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Object> call = () -> { try { return catalogSyncService.sync(); } catch (SyncAlreadyRunningException e) { return e; } };
			Future<Object> first = pool.submit(call);
			Thread.sleep(150);
			Future<Object> second = pool.submit(call);

			Object r1 = first.get(); Object r2 = second.get();
			assertThat(List.of(r1, r2)).anyMatch(SyncReport.class::isInstance);
			assertThat(List.of(r1, r2)).anyMatch(SyncAlreadyRunningException.class::isInstance);
		} finally {
			pool.shutdownNow();
		}
		// 끝난 뒤에는 다시 돌 수 있어야 한다
		assertThat(catalogSyncService.sync().suppliers()).hasSize(2);
	}

	// ── helpers ───────────────────────────────────────────────────────────

	private static SupplierSyncResult result(SyncReport report, String supplier) {
		return report.suppliers().stream().filter(s -> s.supplier().equals(supplier)).findFirst().orElseThrow();
	}

	private Map<String, Long> propertyIdsOf(String supplier) {
		return propertyMappingRepository.findAllBySupplier(supplier).stream()
				.collect(Collectors.toMap(PropertyMapping::getSupplierHotelCode, PropertyMapping::getId));
	}

	private Map<String, Long> roomTypeIdsOf(String supplier) {
		return roomTypeMappingRepository.findAllBySupplier(supplier).stream()
				.collect(Collectors.toMap(r -> r.getSupplierHotelCode() + "|" + r.getSupplierRoomTypeCode(), RoomTypeMapping::getId, (x, y) -> x, java.util.HashMap::new));
	}
}
