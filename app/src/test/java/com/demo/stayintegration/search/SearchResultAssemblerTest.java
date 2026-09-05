package com.demo.stayintegration.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.demo.stayintegration.catalog.dto.CatalogLookup;
import com.demo.stayintegration.catalog.dto.CatalogLookup.RoomTypeKey;
import com.demo.stayintegration.catalog.dto.CatalogLookup.RoomTypeRef;
import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.search.dto.response.SearchResponse;
import com.demo.stayintegration.search.dto.response.StayItem;
import com.demo.stayintegration.search.dto.response.SupplierOutcome;
import com.demo.stayintegration.search.service.SearchResultAssembler;
import com.demo.stayintegration.search.service.SupplierFetchOutcome;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.NightlyRate;
import com.demo.stayintegration.supplier.port.NormalizationIssue;
import com.demo.stayintegration.supplier.port.Offer;
import com.demo.stayintegration.supplier.port.Price;
import com.demo.stayintegration.supplier.port.PriceDetail;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

// 순수 함수라 Spring 없이 상태 표(06 §3)·D-6·D-10·D-11·정렬을 전부 여기서 검증한다.
class SearchResultAssemblerTest {

	private static final SupplierId A = new SupplierId("a");
	private static final SupplierId B = new SupplierId("b");
	private static final LocalDate CHECK_IN = LocalDate.of(2026, 9, 1);
	private static final SearchRequest REQUEST = new SearchRequest(CHECK_IN, CHECK_IN.plusDays(3), 2, 0);
	private static final Duration ELAPSED = Duration.ofMillis(84);

	// 같은 호텔이 A·B에 있어도 내부 숙소는 둘(1과 2)이다 — 공통 키가 없다
	private static final CatalogLookup LOOKUP = new CatalogLookup(
			Map.of(A, List.of("H1"), B, List.of("P1")),
			Map.of(new RoomTypeKey(A, "H1", "R1"), new RoomTypeRef(1, "Hotel One", 10, "Room One", 2),
					new RoomTypeKey(A, "H1", "R2"), new RoomTypeRef(1, "Hotel One", 11, "Room Two", 2),
					new RoomTypeKey(B, "P1", "X1"), new RoomTypeRef(2, "Hotel One", 20, "Room X", 2)));

	private final SearchResultAssembler assembler = new SearchResultAssembler();

	// ── helpers ───────────────────────────────────────────────────────────

	private static Offer offer(SupplierId supplier, String hotelCode, String roomTypeCode, int availableRooms, PriceDetail priceDetail) {
		RoomTypeRef reference = LOOKUP.find(supplier, hotelCode, roomTypeCode).orElse(new RoomTypeRef(0, "Unknown", 0, "Unknown", 2));
		return new Offer(supplier, hotelCode, reference.propertyName(), roomTypeCode, reference.roomTypeName(), reference.maxOccupancy(),
				availableRooms, new Price("KRW", 300_000, true, false, 3), priceDetail);
	}

	private static SupplierResult<AvailabilityResult> success(SupplierId supplier, Offer... offers) {
		return new SupplierResult.Success<>(supplier, new AvailabilityResult(List.of(offers), List.of()), Duration.ofMillis(5));
	}

	private static SupplierResult<AvailabilityResult> failure(SupplierId supplier, FailureKind kind, String detail) {
		return new SupplierResult.Failure<>(supplier, kind, detail, Duration.ofMillis(5));
	}

	private static SupplierResult<AvailabilityResult> skipped(SupplierId supplier, String reason) {
		return new SupplierResult.Skipped<>(supplier, reason, Duration.ZERO);
	}

	@SafeVarargs
	private static SupplierFetchOutcome fetched(SupplierId supplier, SupplierResult<AvailabilityResult>... results) {
		return new SupplierFetchOutcome(supplier, List.of(results), ELAPSED);
	}

	private static SupplierOutcome supplierOutcome(SearchResponse response, String supplier) {
		return response.suppliers().stream().filter(s -> s.supplier().equals(supplier)).findFirst().orElseThrow();
	}

	// ── 상태 표 ───────────────────────────────────────────────────────────

	@Test
	void allSuccessIsOkWithItemsSortedByPropertyThenRoomTypeThenSupplier() {
		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(
				fetched(B, success(B, offer(B, "P1", "X1", 1, null))),
				fetched(A, success(A, offer(A, "H1", "R2", 4, null), offer(A, "H1", "R1", 3, null)))));

		assertThat(response.status()).isEqualTo(SearchResponse.Status.OK);
		assertThat(response.cacheable()).isTrue();
		assertThat(response.suppliers()).extracting(SupplierOutcome::supplier).containsExactly("a", "b");
		assertThat(response.items()).extracting(StayItem::propertyId, StayItem::roomTypeId, StayItem::supplier)
				.containsExactly(org.assertj.core.groups.Tuple.tuple(1L, 10L, "a"), org.assertj.core.groups.Tuple.tuple(1L, 11L, "a"),
						org.assertj.core.groups.Tuple.tuple(2L, 20L, "b"));

		SupplierOutcome a = supplierOutcome(response, "a");
		assertThat(a.status()).isEqualTo(SupplierOutcome.Status.SUCCESS);
		assertThat(a.calls()).isEqualTo(1);
		assertThat(a.failedCalls()).isZero();
		assertThat(a.offers()).isEqualTo(2);
		assertThat(a.elapsedMs()).isEqualTo(84);
		assertThat(a.failure()).isNull();
	}

	@Test
	void oneSupplierFailedIsPartialAndNamesTheFailure() {
		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(
				fetched(A, success(A, offer(A, "H1", "R1", 3, null))),
				fetched(B, failure(B, FailureKind.SERVER_ERROR, "E503 TEMPORARILY_UNAVAILABLE"))));

		assertThat(response.status()).isEqualTo(SearchResponse.Status.PARTIAL);
		assertThat(response.cacheable()).isFalse();
		assertThat(response.items()).hasSize(1);
		SupplierOutcome b = supplierOutcome(response, "b");
		assertThat(b.status()).isEqualTo(SupplierOutcome.Status.FAILED);
		assertThat(b.calls()).isEqualTo(1);
		assertThat(b.failedCalls()).isEqualTo(1);
		assertThat(b.failure().kind()).isEqualTo("SERVER_ERROR");
		assertThat(b.failure().retryable()).isTrue();
		assertThat(b.failure().detail()).isEqualTo("E503 TEMPORARILY_UNAVAILABLE");
	}

	@Test
	void everyCallFailingIsAllFailedAndNonRetryableKindsSaySo() {
		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(
				fetched(A, failure(A, FailureKind.UNAUTHORIZED, "HTTP 401")),
				fetched(B, failure(B, FailureKind.TIMEOUT, "no response within PT2S"))));

		assertThat(response.status()).isEqualTo(SearchResponse.Status.ALL_FAILED);
		assertThat(response.items()).isEmpty();
		assertThat(supplierOutcome(response, "a").failure().retryable()).isFalse();
		assertThat(supplierOutcome(response, "b").failure().retryable()).isTrue();
	}

	@Test
	void mixedChunksWithinOneSupplierArePartialOnBothLevelsAndKeepTheFirstFailure() {
		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(
				fetched(A, success(A, offer(A, "H1", "R1", 3, null)),
						failure(A, FailureKind.TIMEOUT, "no result within search deadline PT3S"),
						failure(A, FailureKind.SERVER_ERROR, "HTTP 503"))));

		assertThat(response.status()).isEqualTo(SearchResponse.Status.PARTIAL);
		SupplierOutcome a = supplierOutcome(response, "a");
		assertThat(a.status()).isEqualTo(SupplierOutcome.Status.PARTIAL);
		assertThat(a.calls()).isEqualTo(3);
		assertThat(a.failedCalls()).isEqualTo(2);
		assertThat(a.offers()).isEqualTo(1);
		assertThat(a.failure().kind()).isEqualTo("TIMEOUT");
	}

	@Test
	void skippedCountsAsNeitherSuccessNorFailure() {
		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(
				fetched(A, success(A, offer(A, "H1", "R1", 3, null))),
				fetched(B, skipped(B, "no mapped properties"))));

		assertThat(response.status()).isEqualTo(SearchResponse.Status.OK);
		SupplierOutcome b = supplierOutcome(response, "b");
		assertThat(b.status()).isEqualTo(SupplierOutcome.Status.SKIPPED);
		assertThat(b.calls()).isZero();
		assertThat(b.failure().kind()).isEqualTo("SKIPPED");
		assertThat(b.failure().retryable()).isFalse();
		assertThat(b.failure().detail()).isEqualTo("no mapped properties");
	}

	@Test
	void everySupplierSkippedIsOkWithEmptyItemsButNotCacheable() {
		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(
				fetched(A, skipped(A, "no mapped properties")),
				fetched(B, skipped(B, "circuit open"))));

		assertThat(response.status()).isEqualTo(SearchResponse.Status.OK);
		assertThat(response.items()).isEmpty();
		assertThat(response.cacheable()).isFalse();
	}

	// ── 항목 변환 ─────────────────────────────────────────────────────────

	@Test
	void unmappedOffersAreExcludedAndCountedWithoutFailingTheSupplier() {
		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(
				fetched(A, success(A, offer(A, "H1", "R1", 3, null), offer(A, "H1", "R9", 1, null)))));

		assertThat(response.status()).isEqualTo(SearchResponse.Status.OK);
		assertThat(response.items()).hasSize(1);
		SupplierOutcome a = supplierOutcome(response, "a");
		assertThat(a.status()).isEqualTo(SupplierOutcome.Status.SUCCESS);
		assertThat(a.offers()).isEqualTo(1);
		assertThat(a.unmapped()).isEqualTo(1);
	}

	@Test
	void availabilityValuesWinOverTheCatalogSnapshotWhileIdsComeFromTheCatalog() {
		Offer renamed = new Offer(A, "H1", "Hotel One (Renovated)", "R1", "Room One Deluxe", 3, 2,
				new Price("KRW", 300_000, true, false, 3), null);

		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(fetched(A, success(A, renamed))));

		StayItem item = response.items().getFirst();
		assertThat(item.propertyId()).isEqualTo(1);
		assertThat(item.roomTypeId()).isEqualTo(10);
		assertThat(item.propertyName()).isEqualTo("Hotel One (Renovated)");
		assertThat(item.roomTypeName()).isEqualTo("Room One Deluxe");
		assertThat(item.maxOccupancy()).isEqualTo(3);
	}

	@Test
	void soldOutOffersStayInTheResponseAsUnavailable() {
		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(fetched(A, success(A, offer(A, "H1", "R1", 0, null)))));

		StayItem item = response.items().getFirst();
		assertThat(item.availableRooms()).isZero();
		assertThat(item.available()).isFalse();
	}

	@Test
	void priceIsConvertedAndPriceDetailIsPassedThroughOrStaysNull() {
		PriceDetail detail = new PriceDetail(39_000, List.of(
				new NightlyRate(CHECK_IN, 120_000, 12_000, 3),
				new NightlyRate(CHECK_IN.plusDays(1), 150_000, 15_000, 1),
				new NightlyRate(CHECK_IN.plusDays(2), 120_000, 12_000, 5)));

		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(
				fetched(A, success(A, offer(A, "H1", "R1", 1, detail))),
				fetched(B, success(B, offer(B, "P1", "X1", 1, null)))));

		StayItem fromA = response.items().stream().filter(i -> i.supplier().equals("a")).findFirst().orElseThrow();
		assertThat(fromA.price().currency()).isEqualTo("KRW");
		assertThat(fromA.price().totalAmount()).isEqualTo(300_000);
		assertThat(fromA.price().taxIncluded()).isTrue();
		assertThat(fromA.price().nights()).isEqualTo(3);
		assertThat(fromA.priceDetail().taxAmount()).isEqualTo(39_000);
		assertThat(fromA.priceDetail().nightlyBreakdown()).hasSize(3);
		assertThat(fromA.priceDetail().nightlyBreakdown().get(1).remainingRooms()).isEqualTo(1);

		StayItem fromB = response.items().stream().filter(i -> i.supplier().equals("b")).findFirst().orElseThrow();
		assertThat(fromB.priceDetail()).isNull();
	}

	@Test
	void rejectedItemsAreCountedButDoNotAffectItems() {
		SupplierResult<AvailabilityResult> withRejected = new SupplierResult.Success<>(A, new AvailabilityResult(
				List.of(offer(A, "H1", "R1", 3, null)),
				List.of(new NormalizationIssue("H1", "R2", "negative amount"), new NormalizationIssue("H1", "R3", "missing currency"))),
				Duration.ofMillis(5));

		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of(fetched(A, withRejected)));

		assertThat(response.items()).hasSize(1);
		assertThat(supplierOutcome(response, "a").rejected()).isEqualTo(2);
		assertThat(supplierOutcome(response, "a").status()).isEqualTo(SupplierOutcome.Status.SUCCESS);
	}

	@Test
	void requestIsEchoedWithNights() {
		SearchResponse response = assembler.assemble(REQUEST, LOOKUP, List.of());

		assertThat(response.checkIn()).isEqualTo(CHECK_IN);
		assertThat(response.checkOut()).isEqualTo(CHECK_IN.plusDays(3));
		assertThat(response.nights()).isEqualTo(3);
		assertThat(response.adults()).isEqualTo(2);
		assertThat(response.children()).isZero();
		assertThat(response.status()).isEqualTo(SearchResponse.Status.OK);
	}
}
