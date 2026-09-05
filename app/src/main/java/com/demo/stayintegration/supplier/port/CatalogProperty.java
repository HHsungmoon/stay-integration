package com.demo.stayintegration.supplier.port;

import java.util.List;

// A의 hotelCode/roomTypeCode도, B의 propertyId/roomId도 여기로 번역된다. catalog는 원래 이름을 모른다.
public record CatalogProperty(String code, String name, List<CatalogRoomType> roomTypes) {

	public CatalogProperty {
		roomTypes = List.copyOf(roomTypes);
	}
}
