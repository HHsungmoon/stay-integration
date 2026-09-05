package com.demo.stayintegration.catalog.function;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.demo.stayintegration.catalog.entity.PropertyMapping;
import com.demo.stayintegration.catalog.entity.RoomTypeMapping;
import com.demo.stayintegration.catalog.repository.PropertyMappingRepository;
import com.demo.stayintegration.catalog.repository.RoomTypeMappingRepository;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.CatalogRoomType;
import com.demo.stayintegration.supplier.port.SupplierId;

import lombok.RequiredArgsConstructor;

// repository 쓰기는 여기서만 한다. 포트 타입(공급사 표준 모델)을 엔티티로 바꾸는 지점이기도 하다.
// 기존 엔티티의 갱신은 도메인 메서드(refresh/deactivate)가 하고 dirty checking으로 반영되므로 여기 없다.
@Component
@RequiredArgsConstructor
public class CatalogMappingStore {

	private final PropertyMappingRepository propertyMappingRepository;
	private final RoomTypeMappingRepository roomTypeMappingRepository;

	// IDENTITY라 save 시점에 insert되어 ID가 생긴다. 객실 타입이 FK로 참조하려면 저장된 엔티티가 필요하다.
	public PropertyMapping createProperty(SupplierId supplierId, CatalogProperty incoming, Instant now) {
		return propertyMappingRepository.save(new PropertyMapping(supplierId, incoming.code(), incoming.name(), now));
	}

	public RoomTypeMapping createRoomType(PropertyMapping property, CatalogRoomType incoming, Instant now) {
		return roomTypeMappingRepository.save(
				new RoomTypeMapping(property, incoming.code(), incoming.name(), incoming.maxOccupancy(), now));
	}
}
