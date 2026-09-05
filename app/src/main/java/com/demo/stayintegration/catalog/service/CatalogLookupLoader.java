package com.demo.stayintegration.catalog.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.demo.stayintegration.catalog.dto.CatalogLookup;
import com.demo.stayintegration.catalog.entity.PropertyMapping;
import com.demo.stayintegration.catalog.entity.RoomTypeMapping;
import com.demo.stayintegration.catalog.function.CatalogMappingReader;
import com.demo.stayintegration.supplier.port.SupplierId;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogLookupLoader {

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
