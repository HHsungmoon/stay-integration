package com.demo.stayintegration.supplier.adapter.b;

import java.util.List;

// B 응답 계약. 패키지 밖으로 나가지 않는다(경계 1) — 그래서 public이 아니다.
// 성공·실패가 같은 봉투로 온다. 실패면 data가 null이고 resultCode만 진실을 말한다.
// 날짜는 String(항목 단위 격리), 금액은 boxed(누락과 0 구분) — A와 같은 이유.
final class SupplierBResponse {

	private SupplierBResponse() {}

	static final String SUCCESS_CODE = "0000";

	record Envelope<T>(String resultCode, String resultMessage, T data) {}

	record Properties(List<Property> items) {}

	record Property(String propertyId, String propertyName, List<Room> rooms) {}

	record Room(String roomId, String roomName, int maxOccupancy) {}

	record Search(List<Item> items) {}

	record Item(String propertyId, String propertyName, String roomId, String roomName,
			Integer maxOccupancy, boolean breakfastIncluded, String currency,
			Long totalPrice, boolean taxIncluded, List<Inventory> inventory) {}

	record Inventory(String date, Integer remainingRooms) {}
}
