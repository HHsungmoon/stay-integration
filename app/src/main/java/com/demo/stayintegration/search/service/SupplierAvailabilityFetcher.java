package com.demo.stayintegration.search.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.demo.stayintegration.catalog.dto.CatalogLookup;
import com.demo.stayintegration.search.SearchProperties;
import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierAdapter;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierRegistry;
import com.demo.stayintegration.supplier.port.SupplierResult;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// 청크 분할·동시성 상한·데드라인(D-9)만 책임진다. 이 클래스는 catalog 패키지의 CatalogLookup을 값으로 받을 뿐 JPA를 모른다.
@Component
@RequiredArgsConstructor
public class SupplierAvailabilityFetcher {

	private final SupplierRegistry supplierRegistry;
	private final SearchProperties searchProperties;

	public Mono<List<SupplierFetchOutcome>> fetchAll(SearchRequest request, CatalogLookup lookup) {
		return Flux.fromIterable(supplierRegistry.all())
				.flatMap(adapter -> fetchSupplier(adapter, request, lookup.hotelCodesOf(adapter.id())))
				.collectList();
	}

	Mono<SupplierFetchOutcome> fetchSupplier(SupplierAdapter adapter, SearchRequest request, List<String> hotelCodes) {
		SupplierId supplierId = adapter.id();
		if (hotelCodes.isEmpty()) {
			// 물어볼 숙소가 없으면 호출하지 않는다. 실패가 아니라 카탈로그의 상태라 Skipped다.
			return Mono.just(new SupplierFetchOutcome(supplierId,
					List.of(new SupplierResult.Skipped<>(supplierId, "no mapped properties", Duration.ZERO)), Duration.ZERO));
		}
		List<List<String>> chunks = partition(hotelCodes, searchProperties.chunkSize());
		Duration deadline = searchProperties.deadline();
		return Mono.defer(() -> {
			long started = System.nanoTime();
			return Flux.fromIterable(chunks)
					.flatMap(chunk -> adapter.fetchAvailability(request.toQuery(chunk))
							// 어댑터는 예외를 던지지 않는 계약이지만, 깨졌을 때 잃는 것은 이 청크 하나다
							.onErrorResume(e -> Mono.just(unexpected(supplierId, e))),
							searchProperties.concurrencyPerSupplier())
					// 데드라인까지 도착한 청크는 살린다(D-9의 "청크 단위 부분 실패 허용"을 데드라인에도 적용).
					// 남은 호출은 취소되고, 안 온 청크 수만큼 TIMEOUT을 채운다 — 청크 수와 결과 수가 같아야 failedCalls가 정직하다.
					.take(deadline)
					.collectList()
					.map(arrived -> fillMissingChunksWithTimeout(supplierId, arrived, chunks.size(), deadline))
					.onErrorResume(e -> Mono.just(List.of(unexpected(supplierId, e))))
					.map(results -> new SupplierFetchOutcome(supplierId, results, Duration.ofNanos(System.nanoTime() - started)));
		});
	}

	static List<List<String>> partition(List<String> hotelCodes, int chunkSize) {
		List<List<String>> chunks = new ArrayList<>();
		for (int from = 0; from < hotelCodes.size(); from += chunkSize) {
			chunks.add(hotelCodes.subList(from, Math.min(from + chunkSize, hotelCodes.size())));
		}
		return chunks;
	}

	private static List<SupplierResult<AvailabilityResult>> fillMissingChunksWithTimeout(SupplierId supplierId,
			List<SupplierResult<AvailabilityResult>> arrived, int chunkCount, Duration deadline) {
		if (arrived.size() == chunkCount) {
			return arrived;
		}
		List<SupplierResult<AvailabilityResult>> results = new ArrayList<>(arrived);
		for (int missing = chunkCount - arrived.size(); missing > 0; missing--) {
			results.add(new SupplierResult.Failure<>(supplierId, FailureKind.TIMEOUT,
					"no result within search deadline " + deadline, deadline));
		}
		return results;
	}

	private static SupplierResult<AvailabilityResult> unexpected(SupplierId supplierId, Throwable e) {
		return new SupplierResult.Failure<>(supplierId, FailureKind.UNEXPECTED,
				"adapter threw " + e.getClass().getSimpleName() + ": " + e.getMessage(), Duration.ZERO);
	}
}
