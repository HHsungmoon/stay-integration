package com.demo.stayintegration.supplier.port;

import java.util.List;

import reactor.core.publisher.Mono;

// 어댑터는 예외를 던지지 않는다 — 모든 결과는 Success·Failure·Skipped 중 하나다.
// 이 계약 덕에 호출하는 쪽이 onErrorResume 없이 병합할 수 있다.
public interface SupplierAdapter {

	SupplierId id();

	// Mono인 이유: 검색 경로의 병렬 호출·타임아웃 제어. 동기화는 블로킹 컨텍스트지만
	// 포트가 두 스타일을 섞는 것보다 동기화 쪽이 한 번 block()하는 편이 낫다.
	Mono<SupplierResult<List<CatalogProperty>>> fetchCatalog();

	Mono<SupplierResult<AvailabilityResult>> fetchAvailability(AvailabilityQuery query);
}
