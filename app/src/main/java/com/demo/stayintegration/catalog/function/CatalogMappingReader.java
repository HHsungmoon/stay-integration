package com.demo.stayintegration.catalog.function;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.demo.stayintegration.catalog.entity.PropertyMapping;
import com.demo.stayintegration.catalog.entity.RoomTypeMapping;
import com.demo.stayintegration.catalog.repository.PropertyMappingRepository;
import com.demo.stayintegration.catalog.repository.RoomTypeMappingRepository;
import com.demo.stayintegration.supplier.port.SupplierId;

import lombok.RequiredArgsConstructor;

// repository 읽기는 여기서만 한다. 서비스는 이 계층만 알기 때문에 서비스끼리 서로를 주입할 이유가 없고,
// 순환 참조가 구조적으로 막힌다. 트랜잭션은 열지 않는다 — 호출한 서비스의 트랜잭션에 참여한다.
@Component
@RequiredArgsConstructor
public class CatalogMappingReader {

	private final PropertyMappingRepository propertyMappingRepository;
	private final RoomTypeMappingRepository roomTypeMappingRepository;

	// 한 공급사의 기존 매핑 전체. 동기화가 diff를 계산할 입력이다. 수천 개여도 메모리에 들어간다.
	public record ExistingMappings(Map<String, PropertyMapping> propertiesByCode,
			Map<String, RoomTypeMapping> roomTypesByKey) {

		public static String roomTypeKey(String hotelCode, String roomTypeCode) {
			return hotelCode + "|" + roomTypeCode;
		}
	}

	public ExistingMappings existingBySupplier(SupplierId supplierId) {
		Map<String, PropertyMapping> propertiesByCode = propertyMappingRepository.findAllBySupplier(supplierId.value()).stream()
				.collect(Collectors.toMap(PropertyMapping::getSupplierHotelCode, Function.identity()));
		Map<String, RoomTypeMapping> roomTypesByKey = roomTypeMappingRepository.findAllBySupplier(supplierId.value()).stream()
				.collect(Collectors.toMap(
						roomType -> ExistingMappings.roomTypeKey(roomType.getSupplierHotelCode(), roomType.getSupplierRoomTypeCode()),
						Function.identity()));
		return new ExistingMappings(propertiesByCode, roomTypesByKey);
	}

	// JOIN FETCH 한 쿼리. 검색 경로가 시작 시점에 한 번 읽는 용도라 N+1이 나면 안 된다.
	public List<RoomTypeMapping> activeRoomTypesWithProperty() {
		return roomTypeMappingRepository.findAllActiveWithProperty();
	}

	public record SupplierCounts(long activeProperties, long inactiveProperties,
			long activeRoomTypes, long inactiveRoomTypes) {}

	public SupplierCounts countsOf(SupplierId supplierId) {
		String supplier = supplierId.value();
		return new SupplierCounts(
				propertyMappingRepository.countBySupplierAndActive(supplier, true),
				propertyMappingRepository.countBySupplierAndActive(supplier, false),
				roomTypeMappingRepository.countBySupplierAndActive(supplier, true),
				roomTypeMappingRepository.countBySupplierAndActive(supplier, false));
	}
}
