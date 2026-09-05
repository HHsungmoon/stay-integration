package com.demo.stayintegration.catalog;

import com.demo.stayintegration.catalog.dto.CatalogLookup;
import com.demo.stayintegration.catalog.entity.PropertyMapping;
import com.demo.stayintegration.catalog.entity.RoomTypeMapping;
import com.demo.stayintegration.catalog.repository.PropertyMappingRepository;
import com.demo.stayintegration.catalog.repository.RoomTypeMappingRepository;
import com.demo.stayintegration.catalog.function.CatalogMappingReader;
import com.demo.stayintegration.catalog.service.CatalogLookupLoader;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

import com.demo.stayintegration.TestcontainersConfiguration;
import com.demo.stayintegration.supplier.port.SupplierId;

import jakarta.persistence.EntityManager;

// 스레드 모델 1의 근거: 검색 경로가 매핑을 "한 번에" 읽어야 리액티브 체인에 JPA가 새지 않는다.
// 통계로 쿼리 수가 1인지 본다 — JOIN FETCH가 빠지면 property 접근마다 쿼리가 나간다(N+1).
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, CatalogLookupLoader.class, CatalogMappingReader.class })
class CatalogLookupLoaderTest {

	private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
	private static final SupplierId A = new SupplierId("A");
	private static final SupplierId B = new SupplierId("B");

	@Autowired PropertyMappingRepository propertyMappingRepository;
	@Autowired RoomTypeMappingRepository roomTypeMappingRepository;
	@Autowired CatalogLookupLoader catalogLookupLoader;
	@Autowired EntityManager em;

	@Test
	void loadsOnlyActiveRoomTypesOfActivePropertiesInASingleQuery() {
		PropertyMapping riverside = propertyMappingRepository.save(new PropertyMapping(A, "A-10023", "Riverside Hotel Seoul", NOW));
		RoomTypeMapping dlx = roomTypeMappingRepository.save(new RoomTypeMapping(riverside, "DLX-TWN", "Deluxe Twin", 2, NOW));
		RoomTypeMapping std = roomTypeMappingRepository.save(new RoomTypeMapping(riverside, "STD-DBL", "Standard Double", 2, NOW));
		std.deactivate(NOW);                                                          // 객실 타입만 비활성

		PropertyMapping namsan = propertyMappingRepository.save(new PropertyMapping(A, "A-10044", "Namsan Garden Stay", NOW));
		roomTypeMappingRepository.save(new RoomTypeMapping(namsan, "STD-DBL", "Standard Double", 2, NOW));
		namsan.deactivate(NOW);                                                       // 숙소가 비활성 → 그 아래 객실은 활성이어도 제외

		PropertyMapping bRiverside = propertyMappingRepository.save(new PropertyMapping(B, "B77120", "Riverside Hotel Seoul", NOW));
		roomTypeMappingRepository.save(new RoomTypeMapping(bRiverside, "R-401", "Deluxe Twin Room", 2, NOW));

		em.flush();
		em.clear();   // 1차 캐시를 비워 로더가 실제로 DB를 치게 한다

		Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
		stats.clear();

		CatalogLookup lookup = catalogLookupLoader.load();

		assertThat(stats.getPrepareStatementCount()).isEqualTo(1);

		assertThat(lookup.hotelCodesOf(A)).containsExactly("A-10023");                // 남산은 비활성 → 물어볼 목록에서 제외
		assertThat(lookup.hotelCodesOf(B)).containsExactly("B77120");
		assertThat(lookup.find(A, "A-10023", "DLX-TWN")).hasValueSatisfying(ref -> {
			assertThat(ref.propertyId()).isEqualTo(riverside.getId());
			assertThat(ref.propertyName()).isEqualTo("Riverside Hotel Seoul");
			assertThat(ref.roomTypeId()).isEqualTo(dlx.getId());
			assertThat(ref.roomTypeName()).isEqualTo("Deluxe Twin");
			assertThat(ref.maxOccupancy()).isEqualTo(2);
		});
		assertThat(lookup.find(A, "A-10023", "STD-DBL")).isEmpty();                   // 비활성 객실 타입
		assertThat(lookup.find(A, "A-10044", "STD-DBL")).isEmpty();                   // 비활성 숙소의 객실 타입
		assertThat(lookup.find(A, "A-10023", "NOPE")).isEmpty();                      // 미매핑(D-10)
	}

	@Test
	void emptyCatalogGivesEmptyLookup() {
		CatalogLookup lookup = catalogLookupLoader.load();
		assertThat(lookup.isEmpty()).isTrue();
		assertThat(lookup.hotelCodesOf(A)).isEmpty();
	}
}
