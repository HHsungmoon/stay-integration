package com.demo.stayintegration.supplier.port;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

// 검색 조건의 검증(30박 상한, 인원)은 search 컨트롤러가 한다(D-13). 여기서는 어댑터가 전제로 삼는 것만 보장한다 —
// 최소 1박이 아니면 stayDates()가 비어 재고 판정과 요금 합산이 의미를 잃는다.
public record AvailabilityQuery(List<String> hotelCodes, LocalDate checkIn, LocalDate checkOut, int adults, int children) {

	// 공급사 계약 — 한 번에 조회할 수 있는 숙소 코드 상한. 초과분 분할은 search의 책임이고, 어댑터는 초과 요청을 호출 없이 거절한다.
	public static final int MAX_CODES = 50;

	public AvailabilityQuery {
		Objects.requireNonNull(hotelCodes, "hotelCodes");
		Objects.requireNonNull(checkIn, "checkIn");
		Objects.requireNonNull(checkOut, "checkOut");
		if (!checkOut.isAfter(checkIn)) {
			throw new IllegalArgumentException("checkOut must be after checkIn: " + checkIn + " ~ " + checkOut);
		}
		hotelCodes = List.copyOf(hotelCodes);
	}

	public int nights() {
		return (int) ChronoUnit.DAYS.between(checkIn, checkOut);
	}

	// 체크아웃일은 숙박일이 아니다(9/1 in, 9/4 out = 9/1, 9/2, 9/3)
	public List<LocalDate> stayDates() {
		return checkIn.datesUntil(checkOut).toList();
	}

	public boolean exceedsCodeLimit() {
		return hotelCodes.size() > MAX_CODES;
	}
}
