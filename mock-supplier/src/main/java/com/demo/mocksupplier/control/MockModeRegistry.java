package com.demo.mocksupplier.control;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

@Component
public class MockModeRegistry {

	public static final Set<String> SUPPLIERS = Set.of("a", "b");
	public static final Set<String> APIS = Set.of("catalog", "availability");

	public record State(MockMode mode, long delayMs) {
		static final State NORMAL = new State(MockMode.NORMAL, 0);
	}

	// (공급사 × API) 단위. 숙소 목록에만 장애를 걸어야 D-8(동기화 실패) 경로를 검증할 수 있다.
	private final ConcurrentMap<String, State> states = new ConcurrentHashMap<>();

	// no-response로 붙잡아 둔 응답. reset 때 끊어 주지 않으면 테스트 간에 연결이 남는다.
	private final Set<DeferredResult<?>> pending = ConcurrentHashMap.newKeySet();

	// 공급사별로 부록 예시 뒤에 붙일 합성 숙소 수. 0이면 예시 그대로. 규모 테스트(50개 청크·데드라인)가 런타임에 올린다.
	private final java.util.concurrent.atomic.AtomicInteger syntheticHotels = new java.util.concurrent.atomic.AtomicInteger();

	public int syntheticHotels() {
		return syntheticHotels.get();
	}

	public void setSyntheticHotels(int count) {
		syntheticHotels.set(Math.max(0, count));
	}

	public State get(String supplier, String api) {
		return states.getOrDefault(key(supplier, api), State.NORMAL);
	}

	public void set(String supplier, String api, MockMode mode, long delayMs) {
		State state = new State(mode, delayMs);
		if (api == null) {
			APIS.forEach(a -> states.put(key(supplier, a), state));
		} else {
			states.put(key(supplier, api), state);
		}
	}

	public void hold(DeferredResult<?> result) {
		pending.add(result);
		result.onCompletion(() -> pending.remove(result));
	}

	public void reset() {
		states.clear();
		syntheticHotels.set(0);
		pending.forEach(r -> r.setErrorResult(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()));
		pending.clear();
	}

	public Map<String, String> snapshot() {
		Map<String, String> view = new TreeMap<>();
		for (String s : SUPPLIERS) {
			for (String a : APIS) {
				State st = get(s, a);
				view.put(key(s, a), st.mode() == MockMode.DELAY
						? st.mode().label() + "(" + st.delayMs() + "ms)"
						: st.mode().label());
			}
		}
		view.put("catalog.synthetic", String.valueOf(syntheticHotels.get()));
		return view;
	}

	private static String key(String supplier, String api) {
		return supplier + "." + api;
	}
}
