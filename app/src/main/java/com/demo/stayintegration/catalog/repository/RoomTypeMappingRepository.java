package com.demo.stayintegration.catalog.repository;

import com.demo.stayintegration.catalog.entity.RoomTypeMapping;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoomTypeMappingRepository extends JpaRepository<RoomTypeMapping, Long> {

	List<RoomTypeMapping> findAllBySupplier(String supplier);

	long countBySupplierAndActive(String supplier, boolean active);

	// 검색 경로는 시작 시점에 이걸 한 번 읽어 Map으로 만든다. JOIN FETCH가 없으면 property 접근마다
	// 쿼리가 나가고(N+1), 그 조회가 리액티브 체인으로 새어 들어가는 씨앗이 된다.
	@Query("select r from RoomTypeMapping r join fetch r.property p where r.active = true and p.active = true")
	List<RoomTypeMapping> findAllActiveWithProperty();
}
