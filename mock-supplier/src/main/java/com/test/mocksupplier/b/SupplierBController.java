package com.test.mocksupplier.b;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import com.test.mocksupplier.b.SupplierBResponse.Envelope;
import com.test.mocksupplier.b.SupplierBResponse.Inventory;
import com.test.mocksupplier.b.SupplierBResponse.Item;
import com.test.mocksupplier.b.SupplierBResponse.Properties;
import com.test.mocksupplier.b.SupplierBResponse.Property;
import com.test.mocksupplier.b.SupplierBResponse.Room;
import com.test.mocksupplier.b.SupplierBResponse.Search;
import com.test.mocksupplier.catalog.MockCatalog;
import com.test.mocksupplier.common.AvailabilityQuery;
import com.test.mocksupplier.common.MockProperties;
import com.test.mocksupplier.common.RequestError;
import com.test.mocksupplier.common.ResponseGate;

@RestController
@RequestMapping("/b/api")
public class SupplierBController {

	private static final String SUPPLIER = "b";

	private final ResponseGate gate;
	private final MockProperties properties;

	public SupplierBController(ResponseGate gate, MockProperties properties) {
		this.gate = gate;
		this.properties = properties;
	}

	@GetMapping("/properties")
	public DeferredResult<ResponseEntity<?>> properties(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
		if (!authorized(apiKey)) {
			return ResponseGate.immediate(reject(RequestError.UNAUTHORIZED, "UNAUTHORIZED"));
		}
		return gate.respond(SUPPLIER, "catalog", this::propertiesBody, this::temporarilyUnavailable);
	}

	@GetMapping("/search")
	public DeferredResult<ResponseEntity<?>> search(
			@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
			@RequestParam(required = false) String propertyIds,
			@RequestParam(required = false) String checkIn,
			@RequestParam(required = false) String checkOut,
			@RequestParam(required = false) String adults,
			@RequestParam(required = false) String children) {

		if (!authorized(apiKey)) {
			return ResponseGate.immediate(reject(RequestError.UNAUTHORIZED, "UNAUTHORIZED"));
		}
		return switch (AvailabilityQuery.parse(propertyIds, checkIn, checkOut, adults, children)) {
			case AvailabilityQuery.Rejected r -> ResponseGate.immediate(reject(r.error(), r.message()));
			case AvailabilityQuery.Ok ok -> gate.respond(SUPPLIER, "availability",
					() -> searchBody(ok.query()), this::temporarilyUnavailable);
		};
	}

	private boolean authorized(String apiKey) {
		return apiKey != null && apiKey.equals(properties.apiKeyOf(SUPPLIER));
	}

	private ResponseEntity<?> propertiesBody() {
		List<Property> items = MockCatalog.B.stream()
				.map(p -> new Property(p.code(), p.name(), p.rooms().stream()
						.map(r -> new Room(r.code(), r.name(), r.maxOccupancy()))
						.toList()))
				.toList();
		return ResponseEntity.ok(Envelope.ok(new Properties(items)));
	}

	private ResponseEntity<?> searchBody(AvailabilityQuery query) {
		List<Item> items = new ArrayList<>();
		for (String code : query.codes()) {
			MockCatalog.findB(code).ifPresent(property -> {
				for (MockCatalog.BRoom room : property.rooms()) {
					if (room.maxOccupancy() < query.guests()) {
						continue;
					}
					List<Inventory> inventory = new ArrayList<>();
					for (int i = 0; i < query.nights(); i++) {
						inventory.add(new Inventory(query.dateAt(i), room.remainingAt(i)));
					}
					items.add(new Item(property.code(), property.name(), room.code(), room.name(),
							room.maxOccupancy(), room.breakfastIncluded(), MockCatalog.CURRENCY,
							room.totalPrice(query.nights()), true, inventory));
				}
			});
		}
		return ResponseEntity.ok(Envelope.ok(new Search(items)));
	}

	// B는 장애여도 HTTP 200이다. 본문 resultCode를 보지 않으면 장애가 "빈 결과"로 둔갑한다.
	private ResponseEntity<?> temporarilyUnavailable() {
		return ResponseEntity.ok(Envelope.fail("E503", "TEMPORARILY_UNAVAILABLE"));
	}

	// 거절도 전부 200. A의 reject()와 같은 enum switch라 한쪽만 고치면 컴파일 에러로 드러난다.
	private static ResponseEntity<?> reject(RequestError error, String message) {
		return switch (error) {
			case UNAUTHORIZED       -> ResponseEntity.ok(Envelope.fail("E401", message));
			case INVALID_PARAMETER  -> ResponseEntity.ok(Envelope.fail("E400", message));
			case INVALID_DATE_RANGE -> ResponseEntity.ok(Envelope.fail("E400", message));
			case TOO_MANY_CODES     -> ResponseEntity.ok(Envelope.fail("E400", message));
		};
	}
}
