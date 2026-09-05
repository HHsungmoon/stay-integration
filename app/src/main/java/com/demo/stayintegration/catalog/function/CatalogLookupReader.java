package com.demo.stayintegration.catalog.function;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.demo.stayintegration.catalog.dto.CatalogLookup;
import com.demo.stayintegration.catalog.entity.PropertyMapping;
import com.demo.stayintegration.catalog.entity.RoomTypeMapping;
import com.demo.stayintegration.supplier.port.SupplierId;

import lombok.RequiredArgsConstructor;

// function인 이유: search 서비스가 이 조립 결과를 쓴다. catalog 서비스를 주입하면 서비스 → 서비스 의존이 되고,
// 그것이 규칙이 막는 순환 참조의 시작이다. 엔티티 → CatalogLookup 조립은 function의 정의("조립·변환")에 맞다.
// 트랜잭션을 열지 않는다 — JOIN FETCH 한 쿼리라 필요 없고, 검색 경로가 트랜잭션 안에서 공급사를 부르는 모양이 되면 안 된다.
@Component
@RequiredArgsConstructor
public class CatalogLookupReader {

	private final CatalogMappingReader catalogMappingReader;

	// 활성 객실 타입이 하나도 없는 숙소는 공급사에 물어볼 이유가 없으므로 코드 목록에서 빠진다.
	public CatalogLookup load() {
		Map<SupplierId, Set<String>> hotelCodes = new LinkedHashMap<>();
		Map<CatalogLookup.RoomTypeKey, CatalogLookup.RoomTypeRef> byKey = new LinkedHashMap<>();

		for (RoomTypeMapping roomType : catalogMappingReader.activeRoomTypesWithProperty()) {
			PropertyMapping property = roomType.getProperty();
			SupplierId supplierId = roomType.supplierId();
			hotelCodes.computeIfAbsent(supplierId, s -> new LinkedHashSet<>()).add(roomType.getSupplierHotelCode());
			byKey.put(new CatalogLookup.RoomTypeKey(supplierId, roomType.getSupplierHotelCode(), roomType.getSupplierRoomTypeCode()),
					new CatalogLookup.RoomTypeRef(property.getId(), property.getSupplierHotelName(),
							roomType.getId(), roomType.getSupplierRoomTypeName(), roomType.getMaxOccupancy()));
		}

		Map<SupplierId, List<String>> hotelCodeLists = new LinkedHashMap<>();
		hotelCodes.forEach((supplierId, codes) -> hotelCodeLists.put(supplierId, List.copyOf(new ArrayList<>(codes))));
		return new CatalogLookup(Map.copyOf(hotelCodeLists), Map.copyOf(byKey));
	}
}
