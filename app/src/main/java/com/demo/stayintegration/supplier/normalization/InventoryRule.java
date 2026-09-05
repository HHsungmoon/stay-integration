package com.demo.stayintegration.supplier.normalization;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// D-5. 두 공급사가 모두 날짜별 재고를 주므로 판정은 하나다. 포트가 아니라 여기 두는 이유 — 포트는 계약만, 로직은 따로.
// 어느 한쪽 어댑터 패키지에 두면 다른 어댑터가 그 패키지에 의존하게 되어 경계 1이 흐려진다.
public final class InventoryRule {

	private InventoryRule() {}

	// 요청 기간 전체를 예약할 수 있는 수 = 날짜별 잔여의 최솟값. 응답에 빠진 날짜는 0 —
	// 팔 수 없는 것을 파는 것보다 못 파는 것이 낫다(과판매 방지). 범위 밖 날짜는 무시, 음수는 0으로 clamp.
	public static int availableRooms(List<LocalDate> stayDates, Map<LocalDate, Integer> remainingByDate) {
		int minimum = Integer.MAX_VALUE;
		for (LocalDate date : stayDates) {
			int remaining = Math.max(0, remainingByDate.getOrDefault(date, 0));
			minimum = Math.min(minimum, remaining);
		}
		return stayDates.isEmpty() ? 0 : minimum;
	}
}
