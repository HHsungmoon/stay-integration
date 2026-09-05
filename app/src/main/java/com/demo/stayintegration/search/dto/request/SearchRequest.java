package com.demo.stayintegration.search.dto.request;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import com.demo.stayintegration.supplier.port.AvailabilityQuery;

// D-13을 생성자에서 검증한다 — 검증을 통과하지 못한 요청 객체는 존재할 수 없다. validate()를 따로 두면 부르는 것을 잊을 수 있다.
// Bean Validation을 쓰지 않는 이유: 교차 검증(체크아웃 > 체크인, 30박)은 클래스 레벨 제약이 필요하고 코드만 늘어 런타임 동작이 같다(규칙 12).
// 과거 체크인은 거부하지 않는다(D-12).
public record SearchRequest(LocalDate checkIn, LocalDate checkOut, int adults, int children) {

	// 상한이 없으면 응답 크기와 공급사 부하가 무제한이 된다(D-13)
	public static final int MAX_NIGHTS = 30;

	public SearchRequest {
		Objects.requireNonNull(checkIn, "checkIn");
		Objects.requireNonNull(checkOut, "checkOut");
		if (adults < 1) {
			throw new InvalidSearchRequestException("INVALID_PARAMETER", "adults must be at least 1: " + adults);
		}
		if (children < 0) {
			throw new InvalidSearchRequestException("INVALID_PARAMETER", "children must not be negative: " + children);
		}
		if (!checkOut.isAfter(checkIn)) {
			throw new InvalidSearchRequestException("INVALID_DATE_RANGE", "checkOut must be after checkIn: " + checkIn + " ~ " + checkOut);
		}
		long nights = ChronoUnit.DAYS.between(checkIn, checkOut);
		if (nights > MAX_NIGHTS) {
			throw new InvalidSearchRequestException("TOO_MANY_NIGHTS", "stay must not exceed " + MAX_NIGHTS + " nights: " + nights);
		}
	}

	public int nights() {
		return (int) ChronoUnit.DAYS.between(checkIn, checkOut);
	}

	public AvailabilityQuery toQuery(List<String> hotelCodes) {
		return new AvailabilityQuery(hotelCodes, checkIn, checkOut, adults, children);
	}
}
