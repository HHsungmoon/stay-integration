package com.demo.stayintegration.search;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import com.demo.stayintegration.supplier.port.AvailabilityQuery;

@ConfigurationProperties(prefix = "search")
public record SearchProperties(
		// 공급사 계약 상한(50). 어댑터가 초과분을 호출 없이 거절하므로 이 값이 상한을 넘으면 검색이 전부 BAD_REQUEST가 된다 — 기동 시 막는다
		@DefaultValue("50") int chunkSize,
		// 한 공급사에 동시에 여는 연결 수. 상한이 없으면 숙소 1,000개 = 20청크가 한 공급사에 동시에 꽂혀 우리가 429를 유발한다.
		// 4는 근거 있는 숫자가 아니라 출발점이다(06 §4) — 계약에 상한이 생기거나 RATE_LIMITED 관측치가 쌓이면 조정한다
		@DefaultValue("4") int concurrencyPerSupplier,
		// D-7 오케스트레이션 데드라인. 공급사 단위로 건다 — 검색 전체에 걸면 하나가 늦을 때 먼저 온 공급사 결과까지 잃는다
		@DefaultValue("3s") Duration deadline) {

	public SearchProperties {
		if (chunkSize < 1 || chunkSize > AvailabilityQuery.MAX_CODES) {
			throw new IllegalArgumentException("search.chunk-size must be within 1.." + AvailabilityQuery.MAX_CODES + ": " + chunkSize);
		}
		if (concurrencyPerSupplier < 1) {
			throw new IllegalArgumentException("search.concurrency-per-supplier must be at least 1: " + concurrencyPerSupplier);
		}
		if (deadline == null || deadline.isZero() || deadline.isNegative()) {
			throw new IllegalArgumentException("search.deadline must be positive: " + deadline);
		}
	}
}
