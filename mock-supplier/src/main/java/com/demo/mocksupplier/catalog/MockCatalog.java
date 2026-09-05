package com.demo.mocksupplier.catalog;

import java.util.List;
import java.util.Optional;

public final class MockCatalog {

	private MockCatalog() {}

	public static final String CURRENCY = "KRW";

	// A: 날짜별 net 단가 + 세금. 패턴은 체크인부터의 인덱스로 순환한다(어떤 기간으로 검색해도 동작).
	public record ARoom(String code, String name, int maxOccupancy, boolean breakfastIncluded, int[] rooms, int[] net) {
		public int remainingAt(int i) { return at(rooms, i); }
		public int netAt(int i)       { return at(net, i); }
		public int taxAt(int i)       { return netAt(i) / 10; }   // 세율 10%
	}

	public record AHotel(String code, String name, List<ARoom> roomTypes) {}

	// B: 기간 총액 gross만. 날짜별 요금은 존재하지 않는다 — 스펙 그대로.
	public record BRoom(String code, String name, int maxOccupancy, boolean breakfastIncluded, int[] rooms, int grossNightly) {
		public int remainingAt(int i)      { return at(rooms, i); }
		public long totalPrice(int nights) { return (long) grossNightly * nights; }
	}

	public record BProperty(String code, String name, List<BRoom> rooms) {}

	public static final List<AHotel> A = List.of(
			new AHotel("A-10023", "Riverside Hotel Seoul", List.of(
					// [3,1,5] — 최솟값 판정을 안 하면 3이나 5로 잘못 나온다
					new ARoom("DLX-TWN", "Deluxe Twin",     2, false, new int[]{3, 1, 5}, new int[]{120_000, 150_000, 120_000}),
					new ARoom("STD-DBL", "Standard Double", 2, false, new int[]{4, 4, 4}, new int[]{ 90_000,  90_000,  90_000}),
					// maxOccupancy 4 — adults=3 검색 때 이것만 나와야 한다(인원 필터 검증)
					new ARoom("FAM-STE", "Family Suite",    4, false, new int[]{2, 2, 2}, new int[]{200_000, 240_000, 200_000})
			)),
			new AHotel("A-10044", "Namsan Garden Stay", List.of(
					// STD-DBL이 A-10023에도 있다 — 의도적. 객실 타입 코드는 숙소 안에서만 유일하므로
					// 매핑 키가 (supplier, hotelCode, roomTypeCode)여야 한다. 키를 잘못 잡으면 여기서 충돌한다.
					// [2,0,4] — 1박이면 예약 가능, 2박 이상이면 매진(연박 최솟값 판정 검증)
					new ARoom("STD-DBL", "Standard Double", 2, false, new int[]{2, 0, 4}, new int[]{ 88_000,  99_000,  88_000})
			))
	);

	public static final List<BProperty> B = List.of(
			// A-10023과 같은 호텔. 공통 키는 없다. 조식 포함이라 A보다 비싸다 — 싼 쪽을 고르면 조건이 다른 상품을 비교하게 된다.
			new BProperty("B77120", "Riverside Hotel Seoul", List.of(
					new BRoom("R-401", "Deluxe Twin Room", 2, true, new int[]{3, 1, 5}, 155_000),
					new BRoom("R-402", "Family Room",      4, true, new int[]{1, 1, 1}, 260_000)
			))
	);

	// 합성 숙소 — 50개 청크 분할·동시성 상한·데드라인이 실제 HTTP로 흐르는지 보기 위한 규모용 데이터.
	// 부록 예시(위)는 그대로 두고 그 뒤에 n개를 결정적으로 붙인다. 코드 형식이 달라(A-9xxxxx / B9xxxxx) 예시와 절대 겹치지 않는다.
	// 객실은 하나씩(items 수 = 숙소 수), 재고는 항상 3, 요금은 인덱스에 따라 조금씩 달라 정렬·합산이 우연히 맞는 일이 없게 한다.
	private static final String SYNTHETIC_A_PREFIX = "A-9";
	private static final String SYNTHETIC_B_PREFIX = "B9";

	public static List<AHotel> hotelsA(int synthetic) {
		List<AHotel> all = new java.util.ArrayList<>(A);
		for (int i = 1; i <= synthetic; i++) all.add(syntheticA(i));
		return all;
	}

	public static List<BProperty> propertiesB(int synthetic) {
		List<BProperty> all = new java.util.ArrayList<>(B);
		for (int i = 1; i <= synthetic; i++) all.add(syntheticB(i));
		return all;
	}

	public static Optional<AHotel> findA(String code, int synthetic) {
		Optional<AHotel> base = A.stream().filter(h -> h.code().equals(code)).findFirst();
		if (base.isPresent()) return base;
		int index = syntheticIndex(code, SYNTHETIC_A_PREFIX);
		return index >= 1 && index <= synthetic ? Optional.of(syntheticA(index)) : Optional.empty();
	}

	public static Optional<BProperty> findB(String code, int synthetic) {
		Optional<BProperty> base = B.stream().filter(p -> p.code().equals(code)).findFirst();
		if (base.isPresent()) return base;
		int index = syntheticIndex(code, SYNTHETIC_B_PREFIX);
		return index >= 1 && index <= synthetic ? Optional.of(syntheticB(index)) : Optional.empty();
	}

	private static AHotel syntheticA(int i) {
		return new AHotel(SYNTHETIC_A_PREFIX + String.format("%05d", i), "Synthetic Hotel A " + i, List.of(
				new ARoom("STD-DBL", "Standard Double", 2, false, new int[]{3, 3, 3}, new int[]{100_000 + i * 10, 100_000 + i * 10, 100_000 + i * 10})));
	}

	private static BProperty syntheticB(int i) {
		return new BProperty(SYNTHETIC_B_PREFIX + String.format("%05d", i), "Synthetic Property B " + i, List.of(
				new BRoom("R-1", "Standard Room", 2, true, new int[]{3, 3, 3}, 130_000 + i * 10)));
	}

	private static int syntheticIndex(String code, String prefix) {
		if (code == null || !code.startsWith(prefix) || code.length() != prefix.length() + 5) return -1;
		try { return Integer.parseInt(code.substring(prefix.length())); } catch (NumberFormatException e) { return -1; }
	}

	private static int at(int[] pattern, int i) {
		return pattern[i % pattern.length];
	}
}
