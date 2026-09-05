package com.demo.stayintegration.supplier.adapter.b;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Envelope;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Inventory;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Item;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Properties;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Property;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Room;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Search;
import com.demo.stayintegration.supplier.adapter.support.CallOutcome;
import com.demo.stayintegration.supplier.adapter.support.ItemDefect;
import com.demo.stayintegration.supplier.normalization.InventoryRule;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.CatalogRoomType;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.NormalizationIssue;
import com.demo.stayintegration.supplier.port.Offer;
import com.demo.stayintegration.supplier.port.Price;

// 순수 함수. B는 항상 HTTP 200이므로 실패 판정도 여기(봉투의 resultCode)에서 한다.
final class SupplierBNormalizer {

	private static final Logger log = LoggerFactory.getLogger(SupplierBNormalizer.class);

	// 본문 코드를 보지 않으면 B의 장애는 "빈 결과"로 둔갑한다. 봉투를 여는 유일한 통로다.
	static <D, R> CallOutcome<R> unwrap(Envelope<D> envelope, Function<D, CallOutcome<R>> onData) {
		if (envelope.resultCode() == null || envelope.resultCode().isBlank()) {
			return CallOutcome.failed(FailureKind.UNEXPECTED, "envelope without resultCode");
		}
		if (!SupplierBResponse.SUCCESS_CODE.equals(envelope.resultCode())) {
			return CallOutcome.failed(kindOf(envelope.resultCode()), describe(envelope));
		}
		if (envelope.data() == null) {
			return CallOutcome.failed(FailureKind.UNEXPECTED, "resultCode 0000 but data is null");
		}
		return onData.apply(envelope.data());
	}

	// 모르는 코드는 재시도하지 않는다(UNEXPECTED). 다만 E5xx/E4xx는 공급사가 코드 체계를 HTTP에 맞춰 짰다고 보고
	// 관대하게 분류한다 — 그 가정은 detail에 남긴다(describe).
	static FailureKind kindOf(String resultCode) {
		return switch (resultCode) {
			case "E500", "E503" -> FailureKind.SERVER_ERROR;
			case "E429" -> FailureKind.RATE_LIMITED;
			case "E401" -> FailureKind.UNAUTHORIZED;
			case "E400" -> FailureKind.BAD_REQUEST;
			default -> resultCode.startsWith("E5") ? FailureKind.SERVER_ERROR
					: resultCode.startsWith("E4") ? FailureKind.BAD_REQUEST
					: FailureKind.UNEXPECTED;
		};
	}

	private static String describe(Envelope<?> envelope) {
		String detail = envelope.resultCode() + " " + envelope.resultMessage();
		return isKnownCode(envelope.resultCode()) ? detail : detail + " (unknown code, classified by prefix)";
	}

	private static boolean isKnownCode(String resultCode) {
		return switch (resultCode) {
			case "E500", "E503", "E429", "E401", "E400" -> true;
			default -> false;
		};
	}

	CallOutcome<List<CatalogProperty>> toCatalog(Properties properties) {
		if (properties.items() == null) {
			return CallOutcome.failed(FailureKind.UNEXPECTED, "properties response has no items");
		}
		List<CatalogProperty> result = new ArrayList<>();
		for (Property property : properties.items()) {
			if (isBlank(property.propertyId())) {
				log.warn("supplier b catalog: skipping property without id (name={})", property.propertyName());
				continue;
			}
			List<CatalogRoomType> roomTypes = new ArrayList<>();
			for (Room room : property.rooms() == null ? List.<Room>of() : property.rooms()) {
				if (isBlank(room.roomId())) {
					log.warn("supplier b catalog: skipping room without id (property={}, name={})", property.propertyId(), room.roomName());
					continue;
				}
				roomTypes.add(new CatalogRoomType(room.roomId(), room.roomName(), room.maxOccupancy()));
			}
			result.add(new CatalogProperty(property.propertyId(), property.propertyName(), roomTypes));
		}
		return CallOutcome.ok(result);
	}

	CallOutcome<AvailabilityResult> toAvailability(AvailabilityQuery query, Search search) {
		if (search.items() == null) {
			return CallOutcome.failed(FailureKind.UNEXPECTED, "search response has no items");
		}
		List<LocalDate> stayDates = query.stayDates();
		List<Offer> offers = new ArrayList<>();
		List<NormalizationIssue> rejected = new ArrayList<>();
		for (Item item : search.items()) {
			try {
				offers.add(toOffer(query, stayDates, item));
			} catch (ItemDefect defect) {
				rejected.add(new NormalizationIssue(item.propertyId(), item.roomId(), defect.getMessage()));
			}
		}
		return CallOutcome.ok(new AvailabilityResult(offers, rejected));
	}

	private Offer toOffer(AvailabilityQuery query, List<LocalDate> stayDates, Item item) {
		if (isBlank(item.propertyId()) || isBlank(item.roomId())) {
			throw new ItemDefect("propertyId/roomId missing");
		}
		if (isBlank(item.currency())) {
			throw new ItemDefect("currency missing");
		}
		if (item.maxOccupancy() == null || item.maxOccupancy() <= 0) {
			throw new ItemDefect("maxOccupancy missing or not positive");
		}
		if (item.totalPrice() == null) {
			throw new ItemDefect("totalPrice missing");
		}
		if (item.totalPrice() < 0) {
			throw new ItemDefect("negative totalPrice");
		}
		if (!item.taxIncluded()) {
			// 표준 요금은 gross다. 세금액을 주지 않는 공급사가 net을 보내면 상향할 재료가 없다 — 지어내지 않는다.
			throw new ItemDefect("taxIncluded=false: cannot normalize to gross without a tax amount");
		}

		Map<LocalDate, Integer> remainingByDate = new HashMap<>();
		for (Inventory inventory : item.inventory() == null ? List.<Inventory>of() : item.inventory()) {
			LocalDate date = parseDate(inventory.date());
			int remaining = inventory.remainingRooms() == null ? 0 : inventory.remainingRooms();
			if (remainingByDate.put(date, remaining) != null) {
				throw new ItemDefect("duplicate date " + date);
			}
		}
		int availableRooms = InventoryRule.availableRooms(stayDates, remainingByDate);

		// 총액은 그대로(D-2). 숙박일수로 나눠 단가를 만들지 않는다 — 없는 값을 지어내는 것. 그래서 priceDetail은 null이다.
		Price price = new Price(item.currency(), item.totalPrice(), true, item.breakfastIncluded(), query.nights());
		return new Offer(SupplierBAdapter.ID, item.propertyId(), item.propertyName(), item.roomId(), item.roomName(),
				item.maxOccupancy(), availableRooms, price, null);
	}

	private static LocalDate parseDate(String value) {
		if (value == null) {
			throw new ItemDefect("date missing in inventory");
		}
		try {
			return LocalDate.parse(value);
		} catch (DateTimeParseException e) {
			throw new ItemDefect("unparseable date '" + value + "'");
		}
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
