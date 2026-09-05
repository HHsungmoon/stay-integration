package com.demo.mocksupplier.b;

import java.time.LocalDate;
import java.util.List;

// record 정의가 곧 B 응답 계약의 문서다. 어댑터 DTO와 공유하지 않는다.
public final class SupplierBResponse {

	private SupplierBResponse() {}

	// 성공·실패 모두 이 봉투로 나간다. 실패 시 data는 null — 스펙 그대로.
	public record Envelope<T>(String resultCode, String resultMessage, T data) {
		static <T> Envelope<T> ok(T data) {
			return new Envelope<>("0000", "SUCCESS", data);
		}
		static Envelope<Void> fail(String code, String message) {
			return new Envelope<>(code, message, null);
		}
	}

	public record Properties(List<Property> items) {}
	public record Property(String propertyId, String propertyName, List<Room> rooms) {}
	public record Room(String roomId, String roomName, int maxOccupancy) {}

	// totalPrice는 기간 전체 gross. 날짜별 요금은 없다.
	public record Search(List<Item> items) {}
	public record Item(String propertyId, String propertyName, String roomId, String roomName,
			int maxOccupancy, boolean breakfastIncluded, String currency,
			long totalPrice, boolean taxIncluded, List<Inventory> inventory) {}
	public record Inventory(LocalDate date, int remainingRooms) {}
}
