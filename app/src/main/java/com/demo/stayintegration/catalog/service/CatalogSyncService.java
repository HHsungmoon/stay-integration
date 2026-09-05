package com.demo.stayintegration.catalog.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.demo.stayintegration.catalog.CatalogProperties;
import com.demo.stayintegration.catalog.dto.response.CatalogSummary;
import com.demo.stayintegration.catalog.dto.response.SyncReport;
import com.demo.stayintegration.catalog.dto.response.SyncReport.SupplierSyncResult;
import com.demo.stayintegration.catalog.function.CatalogMappingReader;
import com.demo.stayintegration.catalog.function.CatalogMappingReader.SupplierCounts;
import com.demo.stayintegration.common.SupplierCallMetrics;
import com.demo.stayintegration.common.SupplierCallMetrics.Api;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierAdapter;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierRegistry;
import com.demo.stayintegration.supplier.port.SupplierResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// 클래스 레벨 @Transactional(readOnly)을 두지 않는 이유: sync()는 트랜잭션 밖이어야 한다.
// 안에 두면 공급사 호출(block) 동안 커넥션을 붙잡고, synchronizer의 쓰기 트랜잭션이 readOnly에 합류해 실패한다.
@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogSyncService {

	private final SupplierRegistry supplierRegistry;
	private final CatalogMappingSynchronizer catalogMappingSynchronizer;
	private final CatalogMappingReader catalogMappingReader;
	private final Clock clock;
	private final CatalogProperties catalogProperties;
	private final SupplierCallMetrics supplierCallMetrics;

	// 동시에 두 번 돌면 유니크 제약이 둘째를 실패시키긴 하지만, 실패로 끝나는 것보다 안 시작하는 편이 낫다.
	// 단일 인스턴스 가정. 다중 인스턴스는 설계 문서의 남는 문제.
	private final AtomicBoolean running = new AtomicBoolean(false);

	public SyncReport sync() {
		if (!running.compareAndSet(false, true)) {
			throw new SyncAlreadyRunningException();
		}
		try {
			Instant startedAt = clock.instant();
			long started = System.nanoTime();

			List<SupplierSyncResult> results = new ArrayList<>();
			for (SupplierResult<List<CatalogProperty>> result : fetchAll()) {
				supplierCallMetrics.record(Api.CATALOG, result);   // 병합 지점 — 리포트와 지표가 같은 순회에서 나온다
				results.add(apply(result, startedAt));
			}

			SyncReport report = new SyncReport(startedAt, (System.nanoTime() - started) / 1_000_000, results);
			log.info("catalog sync: {}", report.summaryLine());
			return report;
		} finally {
			running.set(false);
		}
	}

	@Transactional(readOnly = true)
	public CatalogSummary summary() {
		List<CatalogSummary.SupplierSummary> rows = new ArrayList<>();
		for (SupplierAdapter adapter : supplierRegistry.all()) {
			SupplierCounts counts = catalogMappingReader.countsOf(adapter.id());
			rows.add(new CatalogSummary.SupplierSummary(adapter.id().value(),
					counts.activeProperties(), counts.inactiveProperties(),
					counts.activeRoomTypes(), counts.inactiveRoomTypes()));
		}
		return new CatalogSummary(rows);
	}

	// 병렬 fetch → block 한 번 → 저장은 block 이후 순차. 체인 안에 JPA가 없다.
	// 데드라인을 공급사별로 거는 이유: 병합된 스트림에 걸면 하나가 늦을 때 먼저 온 결과까지 함께 잃는다.
	private List<SupplierResult<List<CatalogProperty>>> fetchAll() {
		Duration deadline = catalogProperties.syncDeadline();
		return Flux.fromIterable(supplierRegistry.all())
				.flatMap(adapter -> adapter.fetchCatalog()
						.timeout(deadline, Mono.fromSupplier(() -> deadlineExceeded(adapter.id(), deadline)))
						// 어댑터는 예외를 던지지 않기로 한 계약이지만, 깨졌을 때 다른 공급사까지 죽이지 않는다
						.onErrorResume(e -> Mono.just(unexpected(adapter.id(), e))))
				.collectList()
				.block();   // 동기화 진입점에서 단 한 번. 톰캣 워커 또는 기동 스레드 위에서 호출된다.
	}

	private SupplierSyncResult apply(SupplierResult<List<CatalogProperty>> result, Instant now) {
		return switch (result) {
			case SupplierResult.Success<List<CatalogProperty>> success -> {
				try {
					yield SupplierSyncResult.success(success.supplier(),
							catalogMappingSynchronizer.synchronize(success.supplier(), success.value(), now));
				} catch (RuntimeException e) {
					// 한 공급사의 저장 실패가 다른 공급사 동기화를 막지 않는다(D-8). 기존 매핑은 롤백으로 그대로다.
					log.atWarn().addKeyValue("supplier", success.supplier().value()).addKeyValue("event", "sync_persist_failed")
							.setCause(e).log("catalog sync: persisting supplier {} failed — keeping existing mappings", success.supplier());
					yield SupplierSyncResult.failed(success.supplier(), "PERSISTENCE", e.getMessage());
				}
			}
			case SupplierResult.Failure<List<CatalogProperty>> failure -> {
				log.atWarn().addKeyValue("supplier", failure.supplier().value()).addKeyValue("event", "sync_call_failed")
						.addKeyValue("kind", failure.kind().name()).addKeyValue("retryable", failure.kind().retryable())
						.log("catalog sync: supplier {} failed ({}: {}) — keeping existing mappings",
								failure.supplier(), failure.kind(), failure.detail());
				yield SupplierSyncResult.failed(failure.supplier(), failure.kind(), failure.detail());
			}
			case SupplierResult.Skipped<List<CatalogProperty>> skipped -> {
				log.atWarn().addKeyValue("supplier", skipped.supplier().value()).addKeyValue("event", "sync_skipped")
						.log("catalog sync: supplier {} skipped ({}) — keeping existing mappings", skipped.supplier(), skipped.reason());
				yield SupplierSyncResult.skipped(skipped.supplier(), skipped.reason());
			}
		};
	}

	private static SupplierResult<List<CatalogProperty>> deadlineExceeded(SupplierId supplierId, Duration deadline) {
		return new SupplierResult.Failure<>(supplierId, FailureKind.TIMEOUT, "catalog fetch exceeded " + deadline, deadline);
	}

	private static SupplierResult<List<CatalogProperty>> unexpected(SupplierId supplierId, Throwable e) {
		return new SupplierResult.Failure<>(supplierId, FailureKind.UNEXPECTED,
				"adapter threw " + e.getClass().getSimpleName() + ": " + e.getMessage(), Duration.ZERO);
	}
}
