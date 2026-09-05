package com.demo.stayintegration.search.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.demo.stayintegration.catalog.dto.CatalogLookup;
import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.search.SearchProperties;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierAdapter;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierRegistry;
import com.demo.stayintegration.supplier.port.SupplierResult;

import reactor.core.publisher.Mono;

// 청크 분할·동시성 상한·데드라인(D-9)을 Spring 없이 검증한다. 어댑터는 포트를 직접 구현한 stub이다.
// 데드라인을 300ms로 줄인 이유: 초과 케이스를 3초 기다리지 않기 위해.
class SupplierAvailabilityFetcherTest {

	private static final Duration DEADLINE = Duration.ofMillis(300);
	private static final Duration BLOCK_LIMIT = Duration.ofSeconds(5);
	private static final SearchRequest REQUEST = new SearchRequest(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0);

	static class StubAdapter implements SupplierAdapter {
		private final SupplierId id;
		final List<AvailabilityQuery> queries = new CopyOnWriteArrayList<>();
		private final AtomicInteger inFlight = new AtomicInteger();
		final AtomicInteger maxInFlight = new AtomicInteger();
		volatile Function<AvailabilityQuery, Mono<SupplierResult<AvailabilityResult>>> behavior = query -> Mono.just(success());
		volatile boolean throwsSynchronously;

		StubAdapter(String id) { this.id = new SupplierId(id); }

		@Override public SupplierId id() { return id; }

		@Override public Mono<SupplierResult<List<CatalogProperty>>> fetchCatalog() {
			return Mono.error(new UnsupportedOperationException("search tests do not fetch the catalog"));
		}

		@Override public Mono<SupplierResult<AvailabilityResult>> fetchAvailability(AvailabilityQuery query) {
			queries.add(query);
			if (throwsSynchronously) {
				throw new IllegalStateException("boom before subscribing");
			}
			return Mono.defer(() -> {
				maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
				// doFinally는 완료 신호가 downstream으로 전파된 뒤 실행되어 다음 청크 구독 뒤에 감소한다(한 개 초과로 측정됨). 전파 전에 줄인다.
				return behavior.apply(query).doOnTerminate(inFlight::decrementAndGet).doOnCancel(inFlight::decrementAndGet);
			});
		}

		SupplierResult<AvailabilityResult> success() {
			return new SupplierResult.Success<>(id, new AvailabilityResult(List.of(), List.of()), Duration.ofMillis(5));
		}
	}

	private static List<String> codes(int count) {
		return IntStream.range(0, count).mapToObj(i -> "H-" + i).toList();
	}

	private static CatalogLookup lookupWith(Map<SupplierId, List<String>> codesBySupplier) {
		return new CatalogLookup(codesBySupplier, Map.of());
	}

	private static SupplierAvailabilityFetcher fetcher(int chunkSize, int concurrency, SupplierAdapter... adapters) {
		return new SupplierAvailabilityFetcher(new SupplierRegistry(List.of(adapters)), new SearchProperties(chunkSize, concurrency, DEADLINE));
	}

	private static SupplierFetchOutcome outcomeOf(List<SupplierFetchOutcome> outcomes, String supplier) {
		return outcomes.stream().filter(o -> o.supplier().value().equals(supplier)).findFirst().orElseThrow();
	}

	// ── 청크 분할 ─────────────────────────────────────────────────────────

	@Test
	void partitionSplitsIntoChunksOfAtMostChunkSize() {
		assertThat(SupplierAvailabilityFetcher.partition(codes(0), 50)).isEmpty();
		assertThat(SupplierAvailabilityFetcher.partition(codes(50), 50)).hasSize(1).allSatisfy(chunk -> assertThat(chunk).hasSize(50));
		assertThat(SupplierAvailabilityFetcher.partition(codes(51), 50)).extracting(List::size).containsExactly(50, 1);
		assertThat(SupplierAvailabilityFetcher.partition(codes(120), 50)).extracting(List::size).containsExactly(50, 50, 20);
	}

	@Test
	void eachChunkIsOneCallCarryingTheRequestDatesAndParty() {
		StubAdapter a = new StubAdapter("a");
		List<SupplierFetchOutcome> outcomes = fetcher(2, 4, a)
				.fetchAll(REQUEST, lookupWith(Map.of(a.id(), codes(5)))).block(BLOCK_LIMIT);

		assertThat(a.queries).extracting(query -> query.hotelCodes().size()).containsExactlyInAnyOrder(2, 2, 1);
		assertThat(a.queries).allSatisfy(query -> {
			assertThat(query.checkIn()).isEqualTo(REQUEST.checkIn());
			assertThat(query.checkOut()).isEqualTo(REQUEST.checkOut());
			assertThat(query.adults()).isEqualTo(2);
		});
		assertThat(outcomeOf(outcomes, "a").results()).hasSize(3).allMatch(SupplierResult.Success.class::isInstance);
	}

	@Test
	void supplierWithoutMappedCodesIsSkippedWithoutBeingCalled() {
		StubAdapter a = new StubAdapter("a");
		StubAdapter b = new StubAdapter("b");
		List<SupplierFetchOutcome> outcomes = fetcher(50, 4, a, b)
				.fetchAll(REQUEST, lookupWith(Map.of(a.id(), codes(1)))).block(BLOCK_LIMIT);

		assertThat(b.queries).isEmpty();
		assertThat(outcomeOf(outcomes, "b").results()).singleElement()
				.isInstanceOfSatisfying(SupplierResult.Skipped.class, skipped -> assertThat(skipped.reason()).isEqualTo("no mapped properties"));
		assertThat(outcomeOf(outcomes, "a").results()).singleElement().isInstanceOf(SupplierResult.Success.class);
	}

	// ── 동시성 상한 ───────────────────────────────────────────────────────

	@Test
	void concurrencyPerSupplierCapsCallsInFlight() {
		StubAdapter a = new StubAdapter("a");
		a.behavior = query -> Mono.just(a.success()).delayElement(Duration.ofMillis(40));

		fetcher(1, 2, a).fetchAll(REQUEST, lookupWith(Map.of(a.id(), codes(6)))).block(BLOCK_LIMIT);

		assertThat(a.queries).hasSize(6);
		assertThat(a.maxInFlight.get()).isEqualTo(2);
	}

	// ── 데드라인: 도착한 청크는 살린다 (D-9, 06 §4 B) ─────────────────────

	@Test
	void chunksArrivingBeforeTheDeadlineSurviveAndMissingOnesBecomeTimeout() {
		StubAdapter a = new StubAdapter("a");
		a.behavior = query -> query.hotelCodes().contains("H-1") ? Mono.never() : Mono.just(a.success());

		long started = System.nanoTime();
		List<SupplierFetchOutcome> outcomes = fetcher(1, 4, a)
				.fetchAll(REQUEST, lookupWith(Map.of(a.id(), codes(3)))).block(BLOCK_LIMIT);
		Duration took = Duration.ofNanos(System.nanoTime() - started);

		SupplierFetchOutcome a_ = outcomeOf(outcomes, "a");
		assertThat(a_.results()).hasSize(3);   // 청크 수와 결과 수가 같아야 failedCalls가 정직하다
		assertThat(a_.results().stream().filter(SupplierResult.Success.class::isInstance)).hasSize(2);
		assertThat(a_.results().stream().filter(SupplierResult.Failure.class::isInstance)).singleElement()
				.isInstanceOfSatisfying(SupplierResult.Failure.class, failure -> {
					assertThat(failure.kind()).isEqualTo(FailureKind.TIMEOUT);
					assertThat(failure.detail()).contains("deadline");
				});
		assertThat(took).isGreaterThanOrEqualTo(DEADLINE).isLessThan(Duration.ofSeconds(2));
		assertThat(a_.elapsed()).isGreaterThanOrEqualTo(DEADLINE);
	}

	@Test
	void supplierExceedingTheDeadlineDoesNotDelayOrDropTheOther() {
		StubAdapter a = new StubAdapter("a");
		StubAdapter b = new StubAdapter("b");
		a.behavior = query -> Mono.never();

		List<SupplierFetchOutcome> outcomes = fetcher(50, 4, a, b)
				.fetchAll(REQUEST, lookupWith(Map.of(a.id(), codes(2), b.id(), codes(2)))).block(BLOCK_LIMIT);

		assertThat(outcomeOf(outcomes, "a").results()).singleElement()
				.isInstanceOfSatisfying(SupplierResult.Failure.class, failure -> assertThat(failure.kind()).isEqualTo(FailureKind.TIMEOUT));
		assertThat(outcomeOf(outcomes, "b").results()).singleElement().isInstanceOf(SupplierResult.Success.class);
	}

	// ── 어댑터 계약 위반 ──────────────────────────────────────────────────

	@Test
	void adapterErrorSignalLosesOnlyThatChunk() {
		StubAdapter a = new StubAdapter("a");
		a.behavior = query -> query.hotelCodes().contains("H-0")
				? Mono.error(new IllegalStateException("boom"))
				: Mono.just(a.success());

		List<SupplierFetchOutcome> outcomes = fetcher(1, 4, a)
				.fetchAll(REQUEST, lookupWith(Map.of(a.id(), codes(3)))).block(BLOCK_LIMIT);

		List<SupplierResult<AvailabilityResult>> results = outcomeOf(outcomes, "a").results();
		assertThat(results).hasSize(3);
		assertThat(results.stream().filter(SupplierResult.Failure.class::isInstance)).singleElement()
				.isInstanceOfSatisfying(SupplierResult.Failure.class, failure -> {
					assertThat(failure.kind()).isEqualTo(FailureKind.UNEXPECTED);
					assertThat(failure.detail()).contains("IllegalStateException");
				});
	}

	@Test
	void adapterThrowingSynchronouslyIsContainedToThatSupplier() {
		StubAdapter a = new StubAdapter("a");
		StubAdapter b = new StubAdapter("b");
		a.throwsSynchronously = true;

		List<SupplierFetchOutcome> outcomes = fetcher(50, 4, a, b)
				.fetchAll(REQUEST, lookupWith(Map.of(a.id(), codes(1), b.id(), codes(1)))).block(BLOCK_LIMIT);

		assertThat(outcomeOf(outcomes, "a").results()).singleElement()
				.isInstanceOfSatisfying(SupplierResult.Failure.class, failure -> assertThat(failure.kind()).isEqualTo(FailureKind.UNEXPECTED));
		assertThat(outcomeOf(outcomes, "b").results()).singleElement().isInstanceOf(SupplierResult.Success.class);
	}
}
