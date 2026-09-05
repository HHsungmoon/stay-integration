package com.demo.stayintegration.search.dto.response;

// (공급사, 숙소, 객실 타입) 한 줄. 같은 호텔이 A·B에 있어도 공통 키가 없어 내부 숙소가 둘이므로 숙소로 묶어도 합쳐지지 않는다 — flat으로 둔다.
// available은 availableRooms > 0의 파생값이지만 병기한다(D-6) — 예약 불가 상품을 빼지 않고 0으로 노출하는 것이 계약이다.
public record StayItem(
		long propertyId, String propertyName,
		long roomTypeId, String roomTypeName,
		int maxOccupancy,
		int availableRooms, boolean available,
		String supplier,
		PriceResponse price,
		PriceDetailResponse priceDetail) {}
