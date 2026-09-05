package com.demo.stayintegration.supplier.adapter.a;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.Availability;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.DailyRate;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.Hotel;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.Hotels;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.Item;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.RoomType;
import com.demo.stayintegration.supplier.adapter.support.CallOutcome;
import com.demo.stayintegration.supplier.adapter.support.ItemDefect;
import com.demo.stayintegration.supplier.normalization.InventoryRule;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.CatalogRoomType;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.NightlyRate;
import com.demo.stayintegration.supplier.port.NormalizationIssue;
import com.demo.stayintegration.supplier.port.Offer;
import com.demo.stayintegration.supplier.port.Price;
import com.demo.stayintegration.supplier.port.PriceDetail;

// 순수 함수. WebClient 없이 단위 테스트한다 — 어댑터에는 호출과 판정만 남긴다.
final class SupplierANormalizer {

	private static final Logger log = LoggerFactory.getLogger(SupplierANormalizer.class);

	CallOutcome<List<CatalogProperty>> toCatalog(Hotels hotels) {
		// items가 없는 응답을 빈 목록으로 읽으면 동기화가 그 공급사의 매핑을 전부 비활성화한다. 봉투 이상으로 본다.
		if (hotels.items() == null) {
			return CallOutcome.failed(FailureKind.UNEXPECTED, "hotels response has no items");
		}
		List<CatalogProperty> properties = new ArrayList<>();
		for (Hotel hotel : hotels.items()) {
			if (isBlank(hotel.hotelCode())) {
				log.warn("supplier a catalog: skipping hotel without code (name={})", hotel.hotelName());
				continue;
			}
			List<CatalogRoomType> roomTypes = new ArrayList<>();
			for (RoomType roomType : hotel.roomTypes() == null ? List.<RoomType>of() : hotel.roomTypes()) {
				if (isBlank(roomType.roomTypeCode())) {
					log.warn("supplier a catalog: skipping room type without code (hotel={}, name={})", hotel.hotelCode(), roomType.roomTypeName());
					continue;
				}
				roomTypes.add(new CatalogRoomType(roomType.roomTypeCode(), roomType.roomTypeName(), roomType.maxOccupancy()));
			}
			properties.add(new CatalogProperty(hotel.hotelCode(), hotel.hotelName(), roomTypes));
		}
		return CallOutcome.ok(properties);
	}

	CallOutcome<AvailabilityResult> toAvailability(AvailabilityQuery query, Availability availability) {
		if (availability.items() == null) {
			return CallOutcome.failed(FailureKind.UNEXPECTED, "availability response has no items");
		}
		List<LocalDate> stayDates = query.stayDates();
		List<Offer> offers = new ArrayList<>();
		List<NormalizationIssue> rejected = new ArrayList<>();
		for (Item item : availability.items()) {
			try {
				offers.add(toOffer(query, stayDates, item));
			} catch (ItemDefect defect) {
				rejected.add(new NormalizationIssue(item.hotelCode(), item.roomTypeCode(), defect.getMessage()));
			}
		}
		return CallOutcome.ok(new AvailabilityResult(offers, rejected));
	}

	private Offer toOffer(AvailabilityQuery query, List<LocalDate> stayDates, Item item) {
		if (isBlank(item.hotelCode()) || isBlank(item.roomTypeCode())) {
			throw new ItemDefect("hotelCode/roomTypeCode missing");
		}
		if (isBlank(item.currency())) {
			throw new ItemDefect("currency missing");
		}
		if (item.maxOccupancy() == null || item.maxOccupancy() <= 0) {
			throw new ItemDefect("maxOccupancy missing or not positive");
		}
		if (item.dailyRates() == null || item.dailyRates().isEmpty()) {
			// A의 요금은 날짜별 단가에서만 만들어진다. 하루치도 없으면 총액을 지어낼 수 없다.
			throw new ItemDefect("dailyRates missing");
		}

		// 요청 기간 안의 날짜만 남긴다. 범위 밖 날짜는 재고 판정에서도 요금 합산에서도 무시한다(D-5).
		Map<LocalDate, DailyRate> ratesInRange = new TreeMap<>();
		for (DailyRate rate : item.dailyRates()) {
			LocalDate date = parseDate(rate.date());
			if (date.isBefore(query.checkIn()) || !date.isBefore(query.checkOut())) {
				continue;
			}
			if (rate.nightlyRate() == null || rate.taxAmount() == null) {
				throw new ItemDefect("nightlyRate/taxAmount missing on " + date);
			}
			if (rate.nightlyRate() < 0 || rate.taxAmount() < 0) {
				throw new ItemDefect("negative amount on " + date);
			}
			if (ratesInRange.put(date, rate) != null) {
				throw new ItemDefect("duplicate date " + date);
			}
		}

		Map<LocalDate, Integer> remainingByDate = new HashMap<>();
		List<NightlyRate> nightlyBreakdown = new ArrayList<>();
		long totalAmount = 0;
		long taxAmount = 0;
		for (Map.Entry<LocalDate, DailyRate> entry : ratesInRange.entrySet()) {
			DailyRate rate = entry.getValue();
			int remaining = rate.remainingRooms() == null ? 0 : rate.remainingRooms();
			remainingByDate.put(entry.getKey(), remaining);
			nightlyBreakdown.add(new NightlyRate(entry.getKey(), rate.nightlyRate(), rate.taxAmount(), remaining));
			// net → gross 상향 정규화(D-2). A는 총액 필드를 주지 않으므로 이 합이 곧 총액이다 — 비교 대상이 없다.
			totalAmount += rate.nightlyRate() + rate.taxAmount();
			taxAmount += rate.taxAmount();
		}

		// 요청 기간 안의 날짜가 빠졌으면 재고는 0(매진)이고 요금은 있는 날짜만의 합이다.
		// 매진 상품의 요금이라 실질 영향은 없어 rejected가 아니라 availableRooms=0으로 살린다.
		int availableRooms = InventoryRule.availableRooms(stayDates, remainingByDate);
		Price price = new Price(item.currency(), totalAmount, true, item.breakfastIncluded(), query.nights());
		return new Offer(SupplierAAdapter.ID, item.hotelCode(), item.hotelName(), item.roomTypeCode(), item.roomTypeName(),
				item.maxOccupancy(), availableRooms, price, new PriceDetail(taxAmount, nightlyBreakdown));
	}

	private static LocalDate parseDate(String value) {
		if (value == null) {
			throw new ItemDefect("date missing in dailyRates");
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
