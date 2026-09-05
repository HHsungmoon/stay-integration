package com.demo.stayintegration.catalog.entity;

import java.time.Instant;

import com.demo.stayintegration.supplier.port.SupplierId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Flyway를 쓰지 않으므로 스키마의 유일한 선언 지점이 이 클래스다. 유니크 제약이 애노테이션에 없으면 DB에도 없다.
@Entity
@Table(name = "property_mapping",
		uniqueConstraints = @UniqueConstraint(name = "uk_property_mapping_supplier_code",
				columnNames = {"supplier", "supplier_hotel_code"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PropertyMapping {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// varchar — SupplierId가 enum이 아니라 String 값 객체이기 때문
	@Column(nullable = false, length = 32)
	private String supplier;

	@Column(name = "supplier_hotel_code", nullable = false, length = 64)
	private String supplierHotelCode;

	// 동기화 시점 스냅샷. 응답에는 재고·요금 응답 값을 우선한다(D-11)
	@Column(name = "supplier_hotel_name", nullable = false)
	private String supplierHotelName;

	// 공급사 목록에서 사라지면 false. 하드 삭제하면 재등장 시 새 ID가 발급되어 멱등성이 깨진다.
	@Column(nullable = false)
	private boolean active;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Column(name = "last_synced_at", nullable = false)
	private Instant lastSyncedAt;

	public PropertyMapping(SupplierId supplier, String supplierHotelCode, String supplierHotelName, Instant now) {
		this.supplier = supplier.value();
		this.supplierHotelCode = supplierHotelCode;
		this.supplierHotelName = supplierHotelName;
		this.active = true;
		this.createdAt = now;
		this.updatedAt = now;
		this.lastSyncedAt = now;
	}

	// 재등장한 상품이 같은 ID로 살아나야 하므로 active=true를 포함한다. 반환값은 "비활성이었다가 살아났는지".
	public boolean refresh(String supplierHotelName, Instant now) {
		boolean reactivated = !this.active;
		this.supplierHotelName = supplierHotelName;
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
