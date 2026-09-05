package com.demo.stayintegration.supplier.port;

import java.util.List;

// 항목 하나의 결함(음수 요금·통화 누락)이 그 공급사의 다른 상품까지 지우지 않게 rejected로 격리한다.
// 응답 전체가 못 쓸 때(파싱 불가·봉투 이상·본문 코드 실패)만 SupplierResult.Failure다.
public record AvailabilityResult(List<Offer> offers, List<NormalizationIssue> rejected) {

	public AvailabilityResult {
		offers = List.copyOf(offers);
		rejected = List.copyOf(rejected);
	}
}
