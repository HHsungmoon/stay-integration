package com.demo.stayintegration.search.dto.response;

import java.util.List;

import com.demo.stayintegration.supplier.port.PriceDetail;

// nullable이 계약이다(D-1) — 날짜별 값을 주는 공급사만 채운다. 총액만 주는 공급사의 것을 나눠서 지어내지 않는다(D-2).
public record PriceDetailResponse(long taxAmount, List<NightlyRateResponse> nightlyBreakdown) {

	public static PriceDetailResponse from(PriceDetail priceDetail) {
		if (priceDetail == null) {
			return null;
		}
		return new PriceDetailResponse(priceDetail.taxAmount(),
				priceDetail.nightlyBreakdown().stream().map(NightlyRateResponse::from).toList());
	}
}
