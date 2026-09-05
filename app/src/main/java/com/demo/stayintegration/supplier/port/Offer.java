package com.demo.stayintegration.supplier.port;

// 재고·요금 응답 1건의 표준형. 숙소·객실 코드는 공급사 코드 그대로다 — 내부 식별자 변환은 search가 CatalogLookup으로 한다.
// 어댑터가 DB를 알면 경계 3이 깨진다. priceDetail은 날짜별 값을 주는 공급사만 채운다(D-1).
public record Offer(
		SupplierId supplier,
		String hotelCode, String hotelName,
		String roomTypeCode, String roomTypeName,
		int maxOccupancy,
		int availableRooms,
		Price price,
		PriceDetail priceDetail) {}
