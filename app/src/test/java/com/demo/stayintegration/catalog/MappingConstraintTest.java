package com.demo.stayintegration.catalog;

import com.demo.stayintegration.catalog.entity.PropertyMapping;
import com.demo.stayintegration.catalog.entity.RoomTypeMapping;
import com.demo.stayintegration.catalog.repository.PropertyMappingRepository;
import com.demo.stayintegration.catalog.repository.RoomTypeMappingRepository;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import com.demo.stayintegration.TestcontainersConfiguration;
import com.demo.stayintegration.supplier.port.SupplierId;

// 유니크 제약이 애노테이션에만 있는 게 아니라 실제 DB에 생겼는지. Flyway가 없으니 이 테스트가 스키마의 유일한 검증이다.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
class MappingConstraintTest {

	private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
	private static final SupplierId A = new SupplierId("A");
	private static final SupplierId B = new SupplierId("B");

	@Autowired PropertyMappingRepository propertyMappingRepository;
	@Autowired RoomTypeMappingRepository roomTypeMappingRepository;

	@Test
	void samePropertyCodeTwiceForSameSupplierIsRejectedByDatabase() {
		propertyMappingRepository.saveAndFlush(new PropertyMapping(A, "A-10023", "Riverside", NOW));
		assertThatThrownBy(() -> propertyMappingRepository.saveAndFlush(new PropertyMapping(A, "A-10023", "Riverside again", NOW)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void samePropertyCodeForDifferentSuppliersIsAllowed() {
		propertyMappingRepository.saveAndFlush(new PropertyMapping(A, "X-1", "Same code", NOW));
		assertThatCode(() -> propertyMappingRepository.saveAndFlush(new PropertyMapping(B, "X-1", "Same code", NOW)))
				.doesNotThrowAnyException();
	}

	@Test
	void sameRoomTypeCodeInDifferentHotelsIsAllowed() {
		// Mock 데이터의 STD-DBL — 객실 타입 코드는 숙소 안에서만 유일하다. 키에 숙소 코드가 없으면 여기서 깨진다.
		PropertyMapping riverside = propertyMappingRepository.saveAndFlush(new PropertyMapping(A, "A-10023", "Riverside", NOW));
		PropertyMapping namsan = propertyMappingRepository.saveAndFlush(new PropertyMapping(A, "A-10044", "Namsan", NOW));
		roomTypeMappingRepository.saveAndFlush(new RoomTypeMapping(riverside, "STD-DBL", "Standard Double", 2, NOW));
		assertThatCode(() -> roomTypeMappingRepository.saveAndFlush(new RoomTypeMapping(namsan, "STD-DBL", "Standard Double", 2, NOW)))
				.doesNotThrowAnyException();
	}

	@Test
	void sameRoomTypeCodeInSameHotelIsRejectedByDatabase() {
		PropertyMapping riverside = propertyMappingRepository.saveAndFlush(new PropertyMapping(A, "A-10023", "Riverside", NOW));
		roomTypeMappingRepository.saveAndFlush(new RoomTypeMapping(riverside, "DLX-TWN", "Deluxe Twin", 2, NOW));
		assertThatThrownBy(() -> roomTypeMappingRepository.saveAndFlush(new RoomTypeMapping(riverside, "DLX-TWN", "Deluxe Twin", 2, NOW)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
