package com.demo.stayintegration.supplier.adapter.a;

import java.util.List;

// A 응답 계약. 패키지 밖으로 나가지 않는다(경계 1) — 그래서 public이 아니다.
// 날짜는 String으로 받는다: LocalDate로 받으면 한 항목의 날짜 형식 오류가 본문 전체의 파싱 실패(Failure)가 되는데,
// 그건 항목 하나의 결함이라 rejected로 격리해야 한다. 금액은 boxed — 누락(null)과 0을 구분해야 한다.
final class SupplierAResponse {

	private SupplierAResponse() {}

	record Hotels(List<Hotel> items) {}

	record Hotel(String hotelCode, String hotelName, List<RoomType> roomTypes) {}

	record RoomType(String roomTypeCode, String roomTypeName, int maxOccupancy) {}

	record Availability(List<Item> items) {}

	record Item(String hotelCode, String hotelName, String roomTypeCode, String roomTypeName,
			Integer maxOccupancy, boolean breakfastIncluded, String currency, List<DailyRate> dailyRates) {}

	record DailyRate(String date, Integer remainingRooms, Long nightlyRate, Long taxAmount) {}

	record Error(String error, String message) {}
}
