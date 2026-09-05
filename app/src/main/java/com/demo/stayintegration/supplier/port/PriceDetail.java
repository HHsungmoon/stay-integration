package com.demo.stayintegration.supplier.port;

import java.util.List;

public record PriceDetail(long taxAmount, List<NightlyRate> nightlyBreakdown) {

	public PriceDetail {
		nightlyBreakdown = List.copyOf(nightlyBreakdown);
	}
}
