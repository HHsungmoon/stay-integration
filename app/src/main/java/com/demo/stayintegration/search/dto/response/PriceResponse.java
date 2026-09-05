package com.demo.stayintegration.search.dto.response;

import com.demo.stayintegration.supplier.port.Price;

// 포트의 Price를 그대로 내보내지 않는다 — 포트 record가 API 계약에 묶이면 포트를 고칠 때 클라이언트가 깨진다.
public record PriceResponse(String currency, long totalAmount, boolean taxIncluded, boolean breakfastIncluded, int nights) {

	public static PriceResponse from(Price price) {
		return new PriceResponse(price.currency(), price.totalAmount(), price.taxIncluded(), price.breakfastIncluded(), price.nights());
	}
}
