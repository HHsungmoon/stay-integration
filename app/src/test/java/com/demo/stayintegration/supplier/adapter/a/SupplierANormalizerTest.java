package com.demo.stayintegration.supplier.adapter.a;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.Availability;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.DailyRate;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.Hotel;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.Hotels;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.Item;
import com.demo.stayintegration.supplier.adapter.a.SupplierAResponse.RoomType;
import com.demo.stayintegration.supplier.adapter.support.CallOutcome;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.NightlyRate;
import com.demo.stayintegration.supplier.port.Offer;

// 순수 함수라 WebClient 없이 본다. 요금 합산(D-2)·재고 판정(D-5)·항목 격리가 대상이다.
class SupplierANormalizerTest {

	static final AvailabilityQuery THREE_NIGHTS = new AvailabilityQuery(List.of("A-10023"),
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0);

	final SupplierANormalizer normalizer = new SupplierANormalizer();

	@Test
	void sumsNetPlusTaxIntoGrossTotalAndFillsPriceDetail() {
		Item item = item(List.of(
				rate("2026-09-01", 3, 120_000, 12_000),
				rate("2026-09-02", 1, 150_000, 15_000),
				rate("2026-09-03", 5, 120_000, 12_000)));

		Offer offer = onlyOffer(normalizer.toAvailability(THREE_NIGHTS, new Availability(List.of(item))));

		assertThat(offer.price().totalAmount()).isEqualTo(429_000);
		assertThat(offer.price().taxIncluded()).isTrue();
		assertThat(offer.price().nights()).isEqualTo(3);
		assertThat(offer.price().currency()).isEqualTo("KRW");
		assertThat(offer.priceDetail().taxAmount()).isEqualTo(39_000);
		assertThat(offer.priceDetail().nightlyBreakdown()).containsExactly(
				new NightlyRate(LocalDate.of(2026, 9, 1), 120_000, 12_000, 3),
				new NightlyRate(LocalDate.of(2026, 9, 2), 150_000, 15_000, 1),
				new NightlyRate(LocalDate.of(2026, 9, 3), 120_000, 12_000, 5));
		assertThat(offer.availableRooms()).isEqualTo(1);
		assertThat(offer.supplier()).isEqualTo(SupplierAAdapter.ID);
	}

	@Test
	void datesOutsideTheStayAreIgnoredForBothInventoryAndPrice() {
		Item item = item(List.of(
				rate("2026-08-31", 0, 999_999, 0),     // 체크인 전날 — 매진이고 비싸지만 무시
				rate("2026-09-01", 2, 100_000, 10_000),
				rate("2026-09-02", 2, 100_000, 10_000),
				rate("2026-09-03", 2, 100_000, 10_000),
				rate("2026-09-04", 0, 999_999, 0)));   // 체크아웃일 — 숙박일이 아니다

		Offer offer = onlyOffer(normalizer.toAvailability(THREE_NIGHTS, new Availability(List.of(item))));

		assertThat(offer.availableRooms()).isEqualTo(2);
		assertThat(offer.price().totalAmount()).isEqualTo(330_000);
		assertThat(offer.priceDetail().nightlyBreakdown()).hasSize(3);
	}

	@Test
	void missingNightMakesTheOfferSoldOutButKeepsItInOffers() {
		Item item = item(List.of(rate("2026-09-01", 3, 100_000, 10_000), rate("2026-09-02", 3, 100_000, 10_000)));

		AvailabilityResult result = value(normalizer.toAvailability(THREE_NIGHTS, new Availability(List.of(item))));

		assertThat(result.rejected()).isEmpty();
		assertThat(result.offers().get(0).availableRooms()).isZero();
		assertThat(result.offers().get(0).price().totalAmount()).isEqualTo(220_000);   // 있는 날짜만의 합
	}

	@Test
	void sortsBreakdownByDateEvenIfSupplierSendsItUnordered() {
		Item item = item(List.of(rate("2026-09-03", 1, 3, 0), rate("2026-09-01", 1, 1, 0), rate("2026-09-02", 1, 2, 0)));

		Offer offer = onlyOffer(normalizer.toAvailability(THREE_NIGHTS, new Availability(List.of(item))));

		assertThat(offer.priceDetail().nightlyBreakdown()).extracting(NightlyRate::net).containsExactly(1L, 2L, 3L);
	}

	@Test
	void defectiveItemIsRejectedWhileTheOthersSurvive() {
		Item healthy = item(List.of(rate("2026-09-01", 1, 1, 0), rate("2026-09-02", 1, 1, 0), rate("2026-09-03", 1, 1, 0)));
		Item negative = new Item("A-10023", "Riverside", "BAD-NEG", "Negative", 2, false, "KRW",
				List.of(rate("2026-09-01", 1, -1, 0), rate("2026-09-02", 1, 1, 0), rate("2026-09-03", 1, 1, 0)));
		Item noCurrency = new Item("A-10023", "Riverside", "BAD-CUR", "No currency", 2, false, null,
				List.of(rate("2026-09-01", 1, 1, 0)));
		Item badDate = new Item("A-10023", "Riverside", "BAD-DATE", "Bad date", 2, false, "KRW",
				List.of(rate("09/01/2026", 1, 1, 0)));
		Item noRates = new Item("A-10023", "Riverside", "BAD-EMPTY", "No rates", 2, false, "KRW", List.of());
		Item missingAmount = new Item("A-10023", "Riverside", "BAD-NULL", "Null rate", 2, false, "KRW",
				List.of(new DailyRate("2026-09-01", 1, null, 0L)));
		Item duplicateDate = new Item("A-10023", "Riverside", "BAD-DUP", "Dup", 2, false, "KRW",
				List.of(rate("2026-09-01", 1, 1, 0), rate("2026-09-01", 1, 1, 0)));
		Item noCode = new Item("A-10023", "Riverside", " ", "Blank code", 2, false, "KRW", List.of(rate("2026-09-01", 1, 1, 0)));
		Item noOccupancy = new Item("A-10023", "Riverside", "BAD-OCC", "No occupancy", null, false, "KRW", List.of(rate("2026-09-01", 1, 1, 0)));

		AvailabilityResult result = value(normalizer.toAvailability(THREE_NIGHTS, new Availability(
				List.of(healthy, negative, noCurrency, badDate, noRates, missingAmount, duplicateDate, noCode, noOccupancy))));

		assertThat(result.offers()).extracting(Offer::roomTypeCode).containsExactly("DLX-TWN");
		assertThat(result.rejected()).hasSize(8);
		assertThat(result.rejected()).allSatisfy(issue -> assertThat(issue.hotelCode()).isEqualTo("A-10023"));
		assertThat(result.rejected()).extracting(issue -> issue.reason()).anySatisfy(reason -> assertThat(reason).contains("negative"));
		assertThat(result.rejected()).extracting(issue -> issue.reason()).anySatisfy(reason -> assertThat(reason).contains("currency"));
		assertThat(result.rejected()).extracting(issue -> issue.reason()).anySatisfy(reason -> assertThat(reason).contains("unparseable date"));
		assertThat(result.rejected()).extracting(issue -> issue.reason()).anySatisfy(reason -> assertThat(reason).contains("duplicate date"));
	}

	@Test
	void missingRemainingRoomsCountsAsZeroNotAsADefect() {
		Item item = item(List.of(new DailyRate("2026-09-01", null, 1L, 0L), rate("2026-09-02", 1, 1, 0), rate("2026-09-03", 1, 1, 0)));

		AvailabilityResult result = value(normalizer.toAvailability(THREE_NIGHTS, new Availability(List.of(item))));

		assertThat(result.rejected()).isEmpty();
		assertThat(result.offers().get(0).availableRooms()).isZero();
	}

	@Test
	void missingItemsIsAnEnvelopeProblemNotAnEmptyResult() {
		CallOutcome<AvailabilityResult> outcome = normalizer.toAvailability(THREE_NIGHTS, new Availability(null));

		assertThat(outcome).isInstanceOf(CallOutcome.Failed.class);
		assertThat(((CallOutcome.Failed<AvailabilityResult>) outcome).kind()).isEqualTo(FailureKind.UNEXPECTED);
	}

	@Test
	void catalogSkipsEntriesWithoutCodesAndKeepsTheRest() {
		Hotels hotels = new Hotels(List.of(
				new Hotel("A-10023", "Riverside", List.of(new RoomType("DLX-TWN", "Deluxe Twin", 2), new RoomType("", "No code", 2))),
				new Hotel(null, "Ghost", List.of(new RoomType("X", "X", 1))),
				new Hotel("A-10044", "Namsan", null)));

		List<CatalogProperty> properties = value(normalizer.toCatalog(hotels));

		assertThat(properties).extracting(CatalogProperty::code).containsExactly("A-10023", "A-10044");
		assertThat(properties.get(0).roomTypes()).hasSize(1);
		assertThat(properties.get(1).roomTypes()).isEmpty();
	}

	@Test
	void catalogWithoutItemsIsUnexpected() {
		assertThat(normalizer.toCatalog(new Hotels(null))).isInstanceOf(CallOutcome.Failed.class);
	}

	// ── helpers ───────────────────────────────────────────────────────────

	static DailyRate rate(String date, int remaining, long net, long tax) {
		return new DailyRate(date, remaining, net, tax);
	}

	static Item item(List<DailyRate> rates) {
		return new Item("A-10023", "Riverside Hotel Seoul", "DLX-TWN", "Deluxe Twin", 2, false, "KRW", rates);
	}

	static <T> T value(CallOutcome<T> outcome) {
		assertThat(outcome).isInstanceOf(CallOutcome.Ok.class);
		return ((CallOutcome.Ok<T>) outcome).value();
	}

	static Offer onlyOffer(CallOutcome<AvailabilityResult> outcome) {
		AvailabilityResult result = value(outcome);
		assertThat(result.rejected()).isEmpty();
		assertThat(result.offers()).hasSize(1);
		return result.offers().get(0);
	}
}
