package com.demo.stayintegration.catalog.dto;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.demo.stayintegration.supplier.port.SupplierId;

// 검색 시작 시점에 한 번 읽어 만드는 in-memory 스냅샷. 리액티브 체인 안에서 JPA를 부르지 않기 위한 장치다.
public record CatalogLookup(
		// 공급사에 물어볼 코드 목록 — 청크 분할(D-9)의 입력
		Map<SupplierId, List<String>> activeHotelCodesBySupplier,
		// 어댑터 응답의 (hotelCode, roomTypeCode)를 내부 식별자로 바꾼다. 없으면 미매핑 상품(D-10)
		Map<RoomTypeKey, RoomTypeRef> byKey) {

	public record RoomTypeKey(SupplierId supplier, String hotelCode, String roomTypeCode) {}

	public record RoomTypeRef(long propertyId, String propertyName, long roomTypeId, String roomTypeName, int maxOccupancy) {}

	public Optional<RoomTypeRef> find(SupplierId supplier, String hotelCode, String roomTypeCode) {
		return Optional.ofNullable(byKey.get(new RoomTypeKey(supplier, hotelCode, roomTypeCode)));
	}

	public List<String> hotelCodesOf(SupplierId supplier) {
		return activeHotelCodesBySupplier.getOrDefault(supplier, List.of());
	}

	public boolean isEmpty() {
		return byKey.isEmpty();
	}
}
