package com.demo.stayintegration.search.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.demo.stayintegration.catalog.dto.CatalogLookup;
import com.demo.stayintegration.catalog.dto.CatalogLookup.RoomTypeRef;
import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.search.dto.response.PriceDetailResponse;
import com.demo.stayintegration.search.dto.response.PriceResponse;
import com.demo.stayintegration.search.dto.response.SearchResponse;
import com.demo.stayintegration.search.dto.response.StayItem;
import com.demo.stayintegration.search.dto.response.SupplierOutcome;
import com.demo.stayintegration.search.dto.response.SupplierOutcome.FailureInfo;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.NormalizationIssue;
import com.demo.stayintegration.supplier.port.Offer;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

import lombok.extern.slf4j.Slf4j;

// 순수 함수 — 입력 셋, 출력 하나, 상태 없음. I/O가 없어 단위 테스트가 전부 여기 붙는다.
// 공급사 결과를 해석하는 유일한 지점이라 관측성 지표(8단계)도 여기 한 곳에 붙는다.
@Component
@Slf4j
public class SearchResultAssembler {

	// 정렬은 범위 밖이지만 출력이 결정적이어야 테스트가 된다
	private static final Comparator<StayItem> ITEM_ORDER = Comparator.comparingLong(StayItem::propertyId)
			.thenComparingLong(StayItem::roomTypeId)
			.thenComparing(StayItem::supplier);

	public SearchResponse assemble(SearchRequest request, CatalogLookup lookup, List<SupplierFetchOutcome> outcomes) {
		List<SupplierOutcome> suppliers = new ArrayList<>();
		List<StayItem> items = new ArrayList<>();
		int succeededCalls = 0;
		int failedCalls = 0;
		for (SupplierFetchOutcome outcome : outcomes) {
			SupplierOutcome supplierOutcome = summarize(outcome, lookup, items);
			suppliers.add(supplierOutcome);
			succeededCalls += supplierOutcome.calls() - supplierOutcome.failedCalls();
			failedCalls += supplierOutcome.failedCalls();
		}
		// flatMap 완료 순서는 비결정적이다
		suppliers.sort(Comparator.comparing(SupplierOutcome::supplier));
		items.sort(ITEM_ORDER);
		return new SearchResponse(overallStatus(succeededCalls, failedCalls),
				request.checkIn(), request.checkOut(), request.nights(), request.adults(), request.children(),
				List.copyOf(suppliers), List.copyOf(items));
	}

	// SKIPPED는 어느 쪽에도 세지 않는다. 전부 SKIPPED면 호출한 청크가 0이라 OK + 빈 items가 된다 — 카탈로그가 빈 것은 장애가 아니다.
	private static SearchResponse.Status overallStatus(int succeededCalls, int failedCalls) {
		if (failedCalls == 0) {
			return SearchResponse.Status.OK;
		}
		return succeededCalls == 0 ? SearchResponse.Status.ALL_FAILED : SearchResponse.Status.PARTIAL;
	}

	private SupplierOutcome summarize(SupplierFetchOutcome outcome, CatalogLookup lookup, List<StayItem> items) {
		SupplierId supplierId = outcome.supplier();
		int calls = 0;
		int failedCalls = 0;
		int skippedCalls = 0;
		int offers = 0;
		int rejected = 0;
		int unmapped = 0;
		FailureInfo firstFailure = null;
		String skippedReason = null;

		for (SupplierResult<AvailabilityResult> result : outcome.results()) {
			switch (result) {
				case SupplierResult.Success<AvailabilityResult> success -> {
					calls++;
					AvailabilityResult availability = success.value();
					rejected += availability.rejected().size();
					// 어댑터는 순수하게 두고 로그는 병합 지점 한 곳에서 남긴다
					for (NormalizationIssue issue : availability.rejected()) {
						log.warn("search: supplier {} rejected item {}/{} — {}", supplierId, issue.hotelCode(), issue.roomTypeCode(), issue.reason());
					}
					for (Offer offer : availability.offers()) {
						Optional<RoomTypeRef> reference = lookup.find(supplierId, offer.hotelCode(), offer.roomTypeCode());
						if (reference.isEmpty()) {
							// D-10: 해당 항목만 제외. 0이 아니면 동기화가 밀렸다는 신호 — 관리 엔드포인트로 동기화를 다시 돌리면 사라진다
							unmapped++;
							log.warn("search: supplier {} returned unmapped item {}/{} — catalog sync may be stale",
									supplierId, offer.hotelCode(), offer.roomTypeCode());
							continue;
						}
						offers++;
						items.add(toItem(supplierId, reference.get(), offer));
					}
				}
				case SupplierResult.Failure<AvailabilityResult> failure -> {
					calls++;
					failedCalls++;
					if (firstFailure == null) {
						firstFailure = new FailureInfo(failure.kind().name(), failure.kind().retryable(), failure.detail());
					}
					log.warn("search: supplier {} call failed ({}: {}) after {}ms", supplierId, failure.kind(), failure.detail(), failure.elapsed().toMillis());
				}
				case SupplierResult.Skipped<AvailabilityResult> skipped -> {
					skippedCalls++;
					skippedReason = skipped.reason();
				}
			}
		}

		SupplierOutcome.Status status = supplierStatus(calls, failedCalls);
		FailureInfo failure = status == SupplierOutcome.Status.SKIPPED ? new FailureInfo("SKIPPED", false, skippedReason) : firstFailure;
		return new SupplierOutcome(supplierId.value(), status, calls, failedCalls, skippedCalls, outcome.elapsed().toMillis(), offers, rejected, unmapped, failure);
	}

	private static SupplierOutcome.Status supplierStatus(int calls, int failedCalls) {
		if (calls == 0) {
			return SupplierOutcome.Status.SKIPPED;
		}
		if (failedCalls == 0) {
			return SupplierOutcome.Status.SUCCESS;
		}
		// 청크 단위 부분 실패 허용(D-9) — 일부 청크만 실패하면 온 것으로 응답하고 사실을 남긴다
		return failedCalls == calls ? SupplierOutcome.Status.FAILED : SupplierOutcome.Status.PARTIAL;
	}

	private StayItem toItem(SupplierId supplierId, RoomTypeRef reference, Offer offer) {
		// D-11: 이름·수용 인원은 재고·요금 응답(②) 값이 요청 시점 기준 최신이다. 카탈로그 스냅샷과 다르면 기록만 한다.
		if (!reference.propertyName().equals(offer.hotelName()) || !reference.roomTypeName().equals(offer.roomTypeName())
				|| reference.maxOccupancy() != offer.maxOccupancy()) {
			log.info("search: supplier {} item {}/{} differs from catalog snapshot — using availability values",
					supplierId, offer.hotelCode(), offer.roomTypeCode());
		}
		return new StayItem(reference.propertyId(), offer.hotelName(), reference.roomTypeId(), offer.roomTypeName(), offer.maxOccupancy(),
				offer.availableRooms(), offer.availableRooms() > 0, supplierId.value(),
				PriceResponse.from(offer.price()), PriceDetailResponse.from(offer.priceDetail()));
	}
}
