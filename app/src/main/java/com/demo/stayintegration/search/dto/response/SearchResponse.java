package com.demo.stayintegration.search.dto.response;

import java.time.LocalDate;
import java.util.List;

// envelope 없음 — status가 곧 판정이다. envelope의 status와 조회 상태가 이중 status가 되는 것을 피한다.
public record SearchResponse(
		Status status,
		LocalDate checkIn, LocalDate checkOut, int nights, int adults, int children,
		List<SupplierOutcome> suppliers,
		List<StayItem> items) {

	// 전 공급사 실패도 HTTP 200 + ALL_FAILED. "검색은 정상 동작했고 결과가 실패"다 — 5xx는 우리 장애로 읽혀 클라이언트 재시도 정책을 오작동시킨다.
	public enum Status { OK, PARTIAL, ALL_FAILED }

	// 중간 캐시가 빈 결과나 부분 결과를 정상 응답으로 캐싱하면 안 된다. 전 공급사 SKIPPED(카탈로그 비었음)는 OK지만 items가 비므로 여기서 걸린다.
	public boolean cacheable() {
		return status == Status.OK && !items.isEmpty();
	}
}
