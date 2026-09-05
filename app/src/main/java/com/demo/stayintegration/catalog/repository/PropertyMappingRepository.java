package com.demo.stayintegration.catalog.repository;

import com.demo.stayintegration.catalog.entity.PropertyMapping;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PropertyMappingRepository extends JpaRepository<PropertyMapping, Long> {

	List<PropertyMapping> findAllBySupplier(String supplier);

	long countBySupplierAndActive(String supplier, boolean active);
}
