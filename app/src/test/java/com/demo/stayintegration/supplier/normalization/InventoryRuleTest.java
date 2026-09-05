package com.demo.stayintegration.supplier.normalization;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

// D-5 표 전부. 이 규칙이 틀리면 과판매가 생기므로 케이스 하나씩 못 박는다.
class InventoryRuleTest {

	static final LocalDate D1 = LocalDate.of(2026, 9, 1);
	static final LocalDate D2 = LocalDate.of(2026, 9, 2);
	static final LocalDate D3 = LocalDate.of(2026, 9, 3);
	static final List<LocalDate> THREE_NIGHTS = List.of(D1, D2, D3);

	@Test
	void minimumOverTheStay() {
		assertThat(InventoryRule.availableRooms(THREE_NIGHTS, Map.of(D1, 3, D2, 1, D3, 5))).isEqualTo(1);
	}

	@Test
	void anySoldOutNightMakesTheWholeStaySoldOut() {
		assertThat(InventoryRule.availableRooms(THREE_NIGHTS, Map.of(D1, 2, D2, 0, D3, 4))).isZero();
	}

	@Test
	void missingNightCountsAsZero() {
		// 3박 요청에 2일치만 왔다 — 팔 수 없는 것을 파는 것보다 못 파는 것이 낫다
		assertThat(InventoryRule.availableRooms(THREE_NIGHTS, Map.of(D1, 3, D2, 3))).isZero();
	}

	@Test
	void datesOutsideTheStayAreIgnored() {
		LocalDate checkOutDay = LocalDate.of(2026, 9, 4);
		assertThat(InventoryRule.availableRooms(THREE_NIGHTS, Map.of(D1, 2, D2, 2, D3, 2, checkOutDay, 0))).isEqualTo(2);
	}

	@Test
	void negativeRemainingIsClampedToZero() {
		assertThat(InventoryRule.availableRooms(List.of(D1), Map.of(D1, -1))).isZero();
	}

	@Test
	void emptyStayHasNoRooms() {
		assertThat(InventoryRule.availableRooms(List.of(), Map.of(D1, 9))).isZero();
	}
}
