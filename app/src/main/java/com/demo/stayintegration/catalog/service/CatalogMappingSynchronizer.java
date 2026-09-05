package com.demo.stayintegration.catalog.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.demo.stayintegration.catalog.dto.response.SyncOutcome;
import com.demo.stayintegration.catalog.entity.PropertyMapping;
import com.demo.stayintegration.catalog.entity.RoomTypeMapping;
import com.demo.stayintegration.catalog.function.CatalogMappingReader;
import com.demo.stayintegration.catalog.function.CatalogMappingReader.ExistingMappings;
import com.demo.stayintegration.catalog.function.CatalogMappingStore;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.CatalogRoomType;
import com.demo.stayintegration.supplier.port.SupplierId;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 공급사 하나의 카탈로그를 매핑에 반영한다. 트랜잭션 하나 — 공급사별로 독립이라 한쪽 실패가 다른 쪽에 안 번진다.
// CatalogSyncService와 분리한 이유: @Transactional은 프록시를 거쳐야 동작하는데 같은 빈 안의 self-invocation은
// 프록시를 거치지 않는다. 그리고 공급사 호출(block)이 트랜잭션 밖에 있어야 커넥션을 네트워크 대기에 묶지 않는다.
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CatalogMappingSynchronizer {

	private final CatalogMappingReader catalogMappingReader;
	private final CatalogMappingStore catalogMappingStore;

	@Transactional
	public SyncOutcome synchronize(SupplierId supplierId, List<CatalogProperty> incoming, Instant now) {
		ExistingMappings existing = catalogMappingReader.existingBySupplier(supplierId);

		int created = 0, updated = 0, reactivated = 0, deactivated = 0;
		Set<String> seenProperties = new HashSet<>();
		Set<String> seenRoomTypes = new HashSet<>();

		for (CatalogProperty incomingProperty : incoming) {
			// 공급사가 같은 코드를 두 번 주면 두 번째 insert가 유니크 제약에 걸려 트랜잭션 전체가 실패한다. 건너뛰고 기록한다.
			if (!seenProperties.add(incomingProperty.code())) {
				log.warn("supplier {} sent duplicate property code {} — ignoring the later one", supplierId, incomingProperty.code());
				continue;
			}

			PropertyMapping property = existing.propertiesByCode().get(incomingProperty.code());
			if (property == null) {
				property = catalogMappingStore.createProperty(supplierId, incomingProperty, now);
				created++;
			} else {
				if (!property.getSupplierHotelName().equals(incomingProperty.name())) {
					log.info("supplier {} property {} renamed: '{}' -> '{}'", supplierId, incomingProperty.code(),
							property.getSupplierHotelName(), incomingProperty.name());
				}
				if (property.refresh(incomingProperty.name(), now)) reactivated++; else updated++;
			}

			for (CatalogRoomType incomingRoomType : incomingProperty.roomTypes()) {
				String key = ExistingMappings.roomTypeKey(incomingProperty.code(), incomingRoomType.code());
				if (!seenRoomTypes.add(key)) {
					log.warn("supplier {} sent duplicate room type {} — ignoring the later one", supplierId, key);
					continue;
				}
				RoomTypeMapping roomType = existing.roomTypesByKey().get(key);
				if (roomType == null) {
					catalogMappingStore.createRoomType(property, incomingRoomType, now);
					created++;
				} else {
					if (roomType.refresh(incomingRoomType.name(), incomingRoomType.maxOccupancy(), now)) reactivated++; else updated++;
				}
			}
		}

		// 목록에서 사라진 것은 비활성화만 한다. 삭제하면 재등장 시 새 ID가 발급되어 멱등성이 깨진다.
		for (PropertyMapping property : existing.propertiesByCode().values()) {
			if (!seenProperties.contains(property.getSupplierHotelCode()) && property.isActive()) {
				property.deactivate(now);
				deactivated++;
			}
		}
		for (RoomTypeMapping roomType : existing.roomTypesByKey().values()) {
			String key = ExistingMappings.roomTypeKey(roomType.getSupplierHotelCode(), roomType.getSupplierRoomTypeCode());
			if (!seenRoomTypes.contains(key) && roomType.isActive()) {
				roomType.deactivate(now);
				deactivated++;
			}
		}

		return new SyncOutcome(created, updated, reactivated, deactivated);
	}
}
