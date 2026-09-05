package com.demo.stayintegration.supplier.adapter.b;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Envelope;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Inventory;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Item;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Properties;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Property;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Room;
import com.demo.stayintegration.supplier.adapter.b.SupplierBResponse.Search;
import com.demo.stayintegration.supplier.adapter.support.CallOutcome;
import com.demo.stayintegration.supplier.port.AvailabilityQuery;
import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.CatalogProperty;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.Offer;

class SupplierBNormalizerTest {

	static final AvailabilityQuery THREE_NIGHTS = new AvailabilityQuery(List.of("B77120"),
			LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 4), 2, 0);

	final SupplierBNormalizer normalizer = new SupplierBNormalizer();

	@Test
	void totalPriceIsTakenAsIsAndPriceDetailIsNull() {
		Item item = item(465_000L, true, List.of(inventory("2026-09-01", 3), inventory("2026-09-02", 1), inventory("2026-09-03", 5)));

		Offer offer = onlyOffer(normalizer.toAvailability(THREE_NIGHTS, new Search(List.of(item))));

		assertThat(offer.price().totalAmount()).isEqualTo(465_000);
		assertThat(offer.price().taxIncluded()).isTrue();
		assertThat(offer.price().breakfastIncluded()).isTrue();
		assertThat(offer.price().nights()).isEqualTo(3);
		assertThat(offer.priceDetail()).isNull();   // 날짜별 값이 없다. 총액 ÷ 박수로 지어내지 않는다
		assertThat(offer.availableRooms()).isEqualTo(1);
		assertThat(offer.supplier()).isEqualTo(SupplierBAdapter.ID);
		assertThat(offer.hotelCode()).isEqualTo("B77120");
		assertThat(offer.roomTypeCode()).isEqualTo("R-401");
	}

	@Test
	void taxExcludedItemIsRejectedBecauseGrossCannotBeDerived() {
		Item item = item(400_000L, false, List.of(inventory("2026-09-01", 1)));

		AvailabilityResult result = value(normalizer.toAvailability(THREE_NIGHTS, new Search(List.of(item))));

		assertThat(result.offers()).isEmpty();
		assertThat(result.rejected()).singleElement().satisfies(issue -> {
			assertThat(issue.hotelCode()).isEqualTo("B77120");
			assertThat(issue.roomTypeCode()).isEqualTo("R-401");
			assertThat(issue.reason()).contains("taxIncluded=false");
		});
	}

	@Test
	void otherDefectsAreRejectedIndividually() {
		Item healthy = item(1L, true, List.of(inventory("2026-09-01", 1), inventory("2026-09-02", 1), inventory("2026-09-03", 1)));
		Item negative = new Item("B77120", "R", "BAD-NEG", "n", 2, true, "KRW", -1L, true, List.of());
		Item noPrice = new Item("B77120", "R", "BAD-NULL", "n", 2, true, "KRW", null, true, List.of());
		Item noCurrency = new Item("B77120", "R", "BAD-CUR", "n", 2, true, "", 1L, true, List.of());
		Item badDate = new Item("B77120", "R", "BAD-DATE", "n", 2, true, "KRW", 1L, true, List.of(inventory("2026/09/01", 1)));
		Item duplicate = new Item("B77120", "R", "BAD-DUP", "n", 2, true, "KRW", 1L, true, List.of(inventory("2026-09-01", 1), inventory("2026-09-01", 2)));
		Item noId = new Item("B77120", "R", null, "n", 2, true, "KRW", 1L, true, List.of());

		AvailabilityResult result = value(normalizer.toAvailability(THREE_NIGHTS, new Search(
				List.of(healthy, negative, noPrice, noCurrency, badDate, duplicate, noId))));

		assertThat(result.offers()).extracting(Offer::roomTypeCode).containsExactly("R-401");
		assertThat(result.rejected()).hasSize(6);
	}

	@Test
	void missingInventoryMeansSoldOutNotADefect() {
		Item item = item(1L, true, null);

		AvailabilityResult result = value(normalizer.toAvailability(THREE_NIGHTS, new Search(List.of(item))));

		assertThat(result.rejected()).isEmpty();
		assertThat(result.offers().get(0).availableRooms()).isZero();
	}

	@Test
	void unwrapTranslatesTheEnvelope() {
		assertThat(SupplierBNormalizer.unwrap(new Envelope<>("0000", "SUCCESS", "payload"), data -> CallOutcome.ok(data + "!")))
				.isEqualTo(CallOutcome.ok("payload!"));
		assertThat(SupplierBNormalizer.unwrap(new Envelope<>("E503", "TEMPORARILY_UNAVAILABLE", null), data -> CallOutcome.ok(data)))
				.isEqualTo(CallOutcome.failed(FailureKind.SERVER_ERROR, "E503 TEMPORARILY_UNAVAILABLE"));
		assertThat(SupplierBNormalizer.unwrap(new Envelope<>("0000", "SUCCESS", null), data -> CallOutcome.ok(data)))
				.isEqualTo(CallOutcome.failed(FailureKind.UNEXPECTED, "resultCode 0000 but data is null"));
		assertThat(SupplierBNormalizer.unwrap(new Envelope<>(null, null, "x"), data -> CallOutcome.ok(data)))
				.isEqualTo(CallOutcome.failed(FailureKind.UNEXPECTED, "envelope without resultCode"));
	}

	@Test
	void resultCodeKinds() {
		assertThat(SupplierBNormalizer.kindOf("E500")).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(SupplierBNormalizer.kindOf("E503")).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(SupplierBNormalizer.kindOf("E429")).isEqualTo(FailureKind.RATE_LIMITED);
		assertThat(SupplierBNormalizer.kindOf("E401")).isEqualTo(FailureKind.UNAUTHORIZED);
		assertThat(SupplierBNormalizer.kindOf("E400")).isEqualTo(FailureKind.BAD_REQUEST);
		assertThat(SupplierBNormalizer.kindOf("E502")).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(SupplierBNormalizer.kindOf("E404")).isEqualTo(FailureKind.BAD_REQUEST);
		assertThat(SupplierBNormalizer.kindOf("E999")).isEqualTo(FailureKind.UNEXPECTED);
		assertThat(SupplierBNormalizer.kindOf("FAIL")).isEqualTo(FailureKind.UNEXPECTED);
	}

	@Test
	void catalogSkipsEntriesWithoutIds() {
		Properties properties = new Properties(List.of(
				new Property("B77120", "Riverside", List.of(new Room("R-401", "Deluxe Twin Room", 2), new Room(" ", "no id", 2))),
				new Property("", "Ghost", List.of())));

		List<CatalogProperty> result = value(normalizer.toCatalog(properties));

		assertThat(result).hasSize(1);
		assertThat(result.get(0).roomTypes()).hasSize(1);
	}

	@Test
	void catalogWithoutItemsIsUnexpected() {
		assertThat(normalizer.toCatalog(new Properties(null))).isInstanceOf(CallOutcome.Failed.class);
	}

	// ── helpers ───────────────────────────────────────────────────────────

	static Inventory inventory(String date, int remaining) {
		return new Inventory(date, remaining);
	}

	static Item item(Long totalPrice, boolean taxIncluded, List<Inventory> inventory) {
		return new Item("B77120", "Riverside Hotel Seoul", "R-401", "Deluxe Twin Room", 2, true, "KRW", totalPrice, taxIncluded, inventory);
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
