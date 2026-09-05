package com.demo.stayintegration.search.dto.response;

import java.time.LocalDate;

import com.demo.stayintegration.supplier.port.NightlyRate;

public record NightlyRateResponse(LocalDate date, long net, long tax, int remainingRooms) {

	public static NightlyRateResponse from(NightlyRate nightlyRate) {
		return new NightlyRateResponse(nightlyRate.date(), nightlyRate.net(), nightlyRate.tax(), nightlyRate.remainingRooms());
	}
}
