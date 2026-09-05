package com.demo.mocksupplier.a;

import java.time.LocalDate;
import java.util.List;

// record 정의가 곧 A 응답 계약의 문서다. 어댑터 DTO와 공유하지 않는다.
public final class SupplierAResponse {

	private SupplierAResponse() {}

	public record Hotels(List<Hotel> items) {}
	public record Hotel(String hotelCode, String hotelName, List<RoomType> roomTypes) {}
	public record RoomType(String roomTypeCode, String roomTypeName, int maxOccupancy) {}

	// items는 (숙소 × 객실 타입) flat
	public record Availability(List<Item> items) {}
	public record Item(String hotelCode, String hotelName, String roomTypeCode, String roomTypeName,
			int maxOccupancy, boolean breakfastIncluded, String currency, List<DailyRate> dailyRates) {}
	public record DailyRate(LocalDate date, int remainingRooms, int nightlyRate, int taxAmount) {}

	public record Error(String error, String message) {}
}
