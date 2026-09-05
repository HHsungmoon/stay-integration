package com.demo.stayintegration.search.service;

import org.springframework.stereotype.Service;

import com.demo.stayintegration.catalog.dto.CatalogLookup;
import com.demo.stayintegration.catalog.function.CatalogLookupReader;
import com.demo.stayintegration.search.dto.request.SearchRequest;
import com.demo.stayintegration.search.dto.response.SearchResponse;

import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

// @Transactional을 두지 않는다. 반환한 Mono는 메서드가 끝난 뒤 컨트롤러에서 구독되므로 트랜잭션 안에서 공급사를 부르는 일은 없지만,
// 애노테이션이 있으면 읽는 사람이 "이 안에서 블로킹 I/O가 돈다"고 오해한다. lookup 읽기는 function의 단일 쿼리라 트랜잭션이 필요 없다.
@Service
@RequiredArgsConstructor
public class StaySearchService {

	private final CatalogLookupReader catalogLookupReader;
	private final SupplierAvailabilityFetcher supplierAvailabilityFetcher;
	private final SearchResultAssembler searchResultAssembler;

	public Mono<SearchResponse> search(SearchRequest request) {
		// JPA는 체인 시작 전에 한 번. 체인 안(fetcher·assembler)은 이 값만 본다 — 스레드 모델 1.
		CatalogLookup lookup = catalogLookupReader.load();
		return supplierAvailabilityFetcher.fetchAll(request, lookup)
				.map(outcomes -> searchResultAssembler.assemble(request, lookup, outcomes));
	}
}
