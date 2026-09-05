package com.demo.stayintegration.catalog.dto.response;

import java.util.List;

public record CatalogSummary(List<SupplierSummary> suppliers) {

	public record SupplierSummary(
			String supplier,
			long activeProperties,
			long inactiveProperties,
			long activeRoomTypes,
			long inactiveRoomTypes) {}
}
