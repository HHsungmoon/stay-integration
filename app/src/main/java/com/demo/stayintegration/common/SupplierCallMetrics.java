package com.demo.stayintegration.common;

import java.util.Locale;

import org.springframework.stereotype.Component;

import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;

// 지표 이름·태그 규약의 유일한 소유자. 병합 지점(검색 service·카탈로그 동기화)은 SupplierResult를 넘길 뿐 태그 이름을 모른다 —
// 이름이 두 곳에 흩어지면 대시보드 쿼리가 한쪽만 맞는 사고가 난다. 새로 측정하지 않는다: SupplierResult가 이미 든 elapsed를 기록만 한다.
// 태그는 값의 개수가 유한한 것만(공급사·API·분류). 숙소 코드·detail·reason 같은 자유 텍스트는 값마다 시계열을 만들어 금지다.
@Component
@RequiredArgsConstructor
public class SupplierCallMetrics {

	public static final String CALL = "supplier.call";
	public static final String CALL_SKIPPED = "supplier.call.skipped";
	public static final String ITEMS_EXCLUDED = "supplier.items.excluded";
	public static final String SEARCH_RESULT = "search.result";

	public enum Api {
		CATALOG("catalog"), AVAILABILITY("availability");

		private final String tag;

		Api(String tag) {
			this.tag = tag;
		}
	}

	public enum ExclusionReason {
		REJECTED("rejected"), UNMAPPED("unmapped");

		private final String tag;

		ExclusionReason(String tag) {
			this.tag = tag;
		}
	}

	private final MeterRegistry meterRegistry;

	public void record(Api api, SupplierResult<?> result) {
		switch (result) {
			case SupplierResult.Success<?> success -> timer(success.supplier(), api, "success").record(success.elapsed());
			// outcome = kind 소문자. 5값으로 접으면 429와 5xx가 합쳐져 동시성 상한의 근거(RATE_LIMITED 비율)를 잃는다
			case SupplierResult.Failure<?> failure ->
					timer(failure.supplier(), api, failure.kind().name().toLowerCase(Locale.ROOT)).record(failure.elapsed());
			// 호출하지 않은 사건은 지연이 없다. Timer에 0을 넣으면 지연 percentile이 아래로 끌려간다 — Counter로 따로 센다
			case SupplierResult.Skipped<?> skipped ->
					meterRegistry.counter(CALL_SKIPPED, "supplier", skipped.supplier().value(), "api", api.tag).increment();
		}
	}

	// unmapped가 0이 아니면 카탈로그 동기화가 밀렸다는 신호(D-10). rejected는 공급사 데이터 품질
	public void recordExcludedItems(SupplierId supplier, ExclusionReason reason, int count) {
		if (count > 0) {
			meterRegistry.counter(ITEMS_EXCLUDED, "supplier", supplier.value(), "reason", reason.tag).increment(count);
		}
	}

	// http.server.requests는 HTTP 200만 본다 — 고객이 본 부분 실패 비율은 이 카운터만 말한다
	public void recordSearchResult(String status) {
		meterRegistry.counter(SEARCH_RESULT, "status", status).increment();
	}

	private Timer timer(SupplierId supplier, Api api, String outcome) {
		return meterRegistry.timer(CALL, "supplier", supplier.value(), "api", api.tag, "outcome", outcome);
	}
}
