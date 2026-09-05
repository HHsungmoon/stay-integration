package com.test.mocksupplier.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

public record AvailabilityQuery(List<String> codes, LocalDate checkIn, LocalDate checkOut, int adults, int children) {

	public static final int MAX_CODES = 50;

	public int nights() {
		return (int) ChronoUnit.DAYS.between(checkIn, checkOut);
	}

	// 스펙: maxOccupancy는 성인+아동 합산 기준
	public int guests() {
		return adults + children;
	}

	public LocalDate dateAt(int index) {
		return checkIn.plusDays(index);
	}

	public sealed interface Result permits Ok, Rejected {}
	public record Ok(AvailabilityQuery query) implements Result {}
	public record Rejected(RequestError error, String message) implements Result {}

	// 파라미터를 String으로 받는 이유: LocalDate/int로 받으면 형식 오류 때 Spring이 자체 400을 돌려주는데,
	// B는 실패해도 HTTP 200이어야 한다. 직접 파싱해서 공급사별 형태로 거절한다.
	public static Result parse(String codesParam, String checkInParam, String checkOutParam,
			String adultsParam, String childrenParam) {

		if (codesParam == null || codesParam.isBlank()) {
			return new Rejected(RequestError.INVALID_PARAMETER, "hotel codes are required");
		}
		List<String> codes = Arrays.stream(codesParam.split(","))
				.map(String::trim)
				.filter(s -> !s.isEmpty())
				.toList();
		if (codes.isEmpty()) {
			return new Rejected(RequestError.INVALID_PARAMETER, "hotel codes are required");
		}
		if (codes.size() > MAX_CODES) {
			return new Rejected(RequestError.TOO_MANY_CODES, "at most " + MAX_CODES + " codes, got " + codes.size());
		}

		LocalDate checkIn = parseDate(checkInParam);
		LocalDate checkOut = parseDate(checkOutParam);
		if (checkIn == null || checkOut == null) {
			return new Rejected(RequestError.INVALID_PARAMETER, "checkIn/checkOut must be YYYY-MM-DD");
		}
		if (!checkOut.isAfter(checkIn)) {
			return new Rejected(RequestError.INVALID_DATE_RANGE, "checkOut must be after checkIn");
		}

		Integer adults = parseNonNegative(adultsParam);
		Integer children = parseNonNegative(childrenParam);
		if (adults == null || children == null) {
			return new Rejected(RequestError.INVALID_PARAMETER, "adults/children must be non-negative integers");
		}

		return new Ok(new AvailabilityQuery(codes, checkIn, checkOut, adults, children));
	}

	private static LocalDate parseDate(String value) {
		if (value == null) return null;
		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	private static Integer parseNonNegative(String value) {
		if (value == null) return null;
		try {
			int n = Integer.parseInt(value.trim());
			return n < 0 ? null : n;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
