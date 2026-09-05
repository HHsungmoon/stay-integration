package com.test.mocksupplier.a;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import com.test.mocksupplier.a.SupplierAResponse.Availability;
import com.test.mocksupplier.a.SupplierAResponse.DailyRate;
import com.test.mocksupplier.a.SupplierAResponse.Error;
import com.test.mocksupplier.a.SupplierAResponse.Hotel;
import com.test.mocksupplier.a.SupplierAResponse.Hotels;
import com.test.mocksupplier.a.SupplierAResponse.Item;
import com.test.mocksupplier.a.SupplierAResponse.RoomType;
import com.test.mocksupplier.catalog.MockCatalog;
import com.test.mocksupplier.common.AvailabilityQuery;
import com.test.mocksupplier.common.MockProperties;
import com.test.mocksupplier.common.RequestError;
import com.test.mocksupplier.common.ResponseGate;

@RestController
@RequestMapping("/a/v1")
public class SupplierAController {

	private static final String SUPPLIER = "a";

	private final ResponseGate gate;
	private final MockProperties properties;

	public SupplierAController(ResponseGate gate, MockProperties properties) {
		this.gate = gate;
		this.properties = properties;
	}

	@GetMapping("/hotels")
	public DeferredResult<ResponseEntity<?>> hotels(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
		if (!authorized(apiKey)) {
			return ResponseGate.immediate(reject(RequestError.UNAUTHORIZED, "invalid api key"));
		}
		return gate.respond(SUPPLIER, "catalog", this::hotelsBody, this::serviceUnavailable);
	}

	@GetMapping("/availability")
	public DeferredResult<ResponseEntity<?>> availability(
			@RequestHeader(value = "X-Api-Key", required = false) String apiKey,
			@RequestParam(required = false) String hotelCodes,
			@RequestParam(required = false) String checkIn,
			@RequestParam(required = false) String checkOut,
			@RequestParam(required = false) String adults,
			@RequestParam(required = false) String children) {

		if (!authorized(apiKey)) {
			return ResponseGate.immediate(reject(RequestError.UNAUTHORIZED, "invalid api key"));
		}
		// 요청 검증은 모드와 무관하게 항상. 검증을 통과한 뒤에만 게이트를 거친다.
		return switch (AvailabilityQuery.parse(hotelCodes, checkIn, checkOut, adults, children)) {
			case AvailabilityQuery.Rejected r -> ResponseGate.immediate(reject(r.error(), r.message()));
			case AvailabilityQuery.Ok ok -> gate.respond(SUPPLIER, "availability",
					() -> availabilityBody(ok.query()), this::serviceUnavailable);
		};
	}

	private boolean authorized(String apiKey) {
		return apiKey != null && apiKey.equals(properties.apiKeyOf(SUPPLIER));
	}

	private ResponseEntity<?> hotelsBody() {
		List<Hotel> hotels = MockCatalog.A.stream()
				.map(h -> new Hotel(h.code(), h.name(), h.roomTypes().stream()
						.map(r -> new RoomType(r.code(), r.name(), r.maxOccupancy()))
						.toList()))
				.toList();
		return ResponseEntity.ok(new Hotels(hotels));
	}

	private ResponseEntity<?> availabilityBody(AvailabilityQuery query) {
		List<Item> items = new ArrayList<>();
		for (String code : query.codes()) {
			// 모르는 코드는 오류 없이 무시한다 — 실제 공급사도 그렇게 동작할 것이다
			MockCatalog.findA(code).ifPresent(hotel -> {
				for (MockCatalog.ARoom room : hotel.roomTypes()) {
					if (room.maxOccupancy() < query.guests()) {
						continue;   // 스펙: 요청 인원을 수용 가능한 객실 타입만 반환
					}
					List<DailyRate> rates = new ArrayList<>();
					for (int i = 0; i < query.nights(); i++) {
						rates.add(new DailyRate(query.dateAt(i), room.remainingAt(i), room.netAt(i), room.taxAt(i)));
					}
					items.add(new Item(hotel.code(), hotel.name(), room.code(), room.name(),
							room.maxOccupancy(), room.breakfastIncluded(), MockCatalog.CURRENCY, rates));
				}
			});
		}
		return ResponseEntity.ok(new Availability(items));
	}

	// A는 실패를 HTTP 상태 코드로 알린다
	private ResponseEntity<?> serviceUnavailable() {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(new Error("SERVICE_UNAVAILABLE", "temporarily unavailable"));
	}

	private static ResponseEntity<?> reject(RequestError error, String message) {
		return switch (error) {
			case UNAUTHORIZED       -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new Error("UNAUTHORIZED", message));
			case INVALID_PARAMETER  -> ResponseEntity.badRequest().body(new Error("INVALID_PARAMETER", message));
			case INVALID_DATE_RANGE -> ResponseEntity.badRequest().body(new Error("INVALID_DATE_RANGE", message));
			case TOO_MANY_CODES     -> ResponseEntity.badRequest().body(new Error("TOO_MANY_HOTEL_CODES", message));
		};
	}
}
