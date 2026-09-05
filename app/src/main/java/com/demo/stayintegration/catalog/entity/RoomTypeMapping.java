package com.demo.stayintegration.catalog.entity;

import java.time.Instant;

import com.demo.stayintegration.supplier.port.SupplierId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 유니크 키에 숙소 코드가 들어가는 이유: 객실 타입 코드는 숙소 안에서만 유일하다.
// Mock의 STD-DBL이 A-10023과 A-10044 양쪽에 있는 것이 그 케이스다.
@Entity
@Table(name = "room_type_mapping",
		uniqueConstraints = @UniqueConstraint(name = "uk_room_type_mapping_supplier_hotel_room",
				columnNames = {"supplier", "supplier_hotel_code", "supplier_room_type_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RoomTypeMapping {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "property_id", nullable = false)
	private PropertyMapping property;

	// property의 값을 생성자에서 복사하고 이후 바꾸지 않는다. 복사하는 이유: 검색 lookup이 join 없이
	// 단일 테이블로 끝나고, 유니크 제약이 스펙의 식별자 규칙(세 값)을 그대로 말한다.
	@Column(nullable = false, length = 32)
	private String supplier;

	@Column(name = "supplier_hotel_code", nullable = false, length = 64)
	private String supplierHotelCode;

	@Column(name = "supplier_room_type_code", nullable = false, length = 64)
	private String supplierRoomTypeCode;

	@Column(name = "supplier_room_type_name", nullable = false)
	private String supplierRoomTypeName;

	// 스냅샷. 응답에는 재고·요금 응답 값을 우선한다(D-11)
	@Column(name = "max_occupancy", nullable = false)
	private int maxOccupancy;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "last_synced_at", nullable = false)
	private Instant lastSyncedAt;

	public RoomTypeMapping(PropertyMapping property, String supplierRoomTypeCode, String supplierRoomTypeName,
			int maxOccupancy, Instant now) {
		this.property = property;
		this.supplier = property.getSupplier();
		this.supplierHotelCode = property.getSupplierHotelCode();
		this.supplierRoomTypeCode = supplierRoomTypeCode;
		this.supplierRoomTypeName = supplierRoomTypeName;
		this.maxOccupancy = maxOccupancy;
		this.active = true;
		this.createdAt = now;
		this.updatedAt = now;
		this.lastSyncedAt = now;
	}

	public boolean refresh(String supplierRoomTypeName, int maxOccupancy, Instant now) {
		boolean reactivated = !this.active;
		this.supplierRoomTypeName = supplierRoomTypeName;
		this.maxOccupancy = maxOccupancy;
		this.active = true;
		this.updatedAt = now;
		this.lastSyncedAt = now;
		return reactivated;
	}

	public void deactivate(Instant now) {
		if (this.active) {
			this.active = false;
			this.updatedAt = now;
		}
	}

	public SupplierId supplierId() {
		return new SupplierId(supplier);
	}
}
