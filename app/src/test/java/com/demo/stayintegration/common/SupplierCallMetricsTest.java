package com.demo.stayintegration.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.demo.stayintegration.common.SupplierCallMetrics.Api;
import com.demo.stayintegration.common.SupplierCallMetrics.ExclusionReason;
import com.demo.stayintegration.supplier.port.FailureKind;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

// 태그 규약(08 §1·§2·§5)을 고정한다. 이름·태그가 바뀌면 대시보드 쿼리가 깨지므로 여기서 잡혀야 한다.
class SupplierCallMetricsTest {

	private static final SupplierId A = new SupplierId("a");

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final SupplierCallMetrics metrics = new SupplierCallMetrics(registry);

	private Timer timer(String api, String outcome) {
		return registry.find(SupplierCallMetrics.CALL).tag("supplier", "a").tag("api", api).tag("outcome", outcome).timer();
	}

	@Test
	void successIsTimedUnderOutcomeSuccessWithTheElapsedCarriedByTheResult() {
		metrics.record(Api.AVAILABILITY, new SupplierResult.Success<>(A, List.of(), Duration.ofMillis(84)));

		Timer timer = timer("availability", "success");
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
		assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(84.0);   // 새로 재지 않는다 — SupplierResult의 elapsed 그대로
	}

	@ParameterizedTest
	@EnumSource(FailureKind.class)
	void failureOutcomeIsTheLowercasedKindSoNoKindIsFoldedAway(FailureKind kind) {
		// 5값으로 접으면 RATE_LIMITED와 SERVER_ERROR가 합쳐져 429 비율(동시성 상한의 근거)을 잃는다 — 8종이 그대로 보여야 한다
		metrics.record(Api.CATALOG, new SupplierResult.Failure<>(A, kind, "detail", Duration.ofMillis(61)));

		Timer timer = timer("catalog", kind.name().toLowerCase(Locale.ROOT));
		assertThat(timer).isNotNull();
		assertThat(timer.count()).isEqualTo(1);
	}

	@Test
	void skippedIsCountedButNeverTimed() {
		metrics.record(Api.AVAILABILITY, new SupplierResult.Skipped<>(A, "circuit open", Duration.ZERO));

		assertThat(registry.find(SupplierCallMetrics.CALL_SKIPPED).tag("supplier", "a").tag("api", "availability").counter().count()).isEqualTo(1.0);
		assertThat(registry.find(SupplierCallMetrics.CALL).timers()).isEmpty();   // elapsed 0이 지연 percentile을 왜곡하면 안 된다
		// 자유 텍스트(reason)는 태그가 아니다 — 값마다 시계열이 생긴다
		assertThat(registry.find(SupplierCallMetrics.CALL_SKIPPED).counter().getId().getTags()).noneMatch(tag -> tag.getKey().equals("reason"));
	}

	@Test
	void excludedItemsAndSearchResultAreCountedAndZeroCreatesNoMeter() {
		metrics.recordExcludedItems(A, ExclusionReason.UNMAPPED, 3);
		metrics.recordExcludedItems(A, ExclusionReason.REJECTED, 0);
		metrics.recordSearchResult("PARTIAL");

		assertThat(registry.find(SupplierCallMetrics.ITEMS_EXCLUDED).tag("supplier", "a").tag("reason", "unmapped").counter().count()).isEqualTo(3.0);
		assertThat(registry.find(SupplierCallMetrics.ITEMS_EXCLUDED).tag("reason", "rejected").counter()).isNull();
		assertThat(registry.find(SupplierCallMetrics.SEARCH_RESULT).tag("status", "PARTIAL").counter().count()).isEqualTo(1.0);
	}
}
