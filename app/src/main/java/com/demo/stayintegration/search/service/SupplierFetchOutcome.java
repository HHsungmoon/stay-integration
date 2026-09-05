package com.demo.stayintegration.search.service;

import java.time.Duration;
import java.util.List;

import com.demo.stayintegration.supplier.port.AvailabilityResult;
import com.demo.stayintegration.supplier.port.SupplierId;
import com.demo.stayintegration.supplier.port.SupplierResult;

// 공급사 하나의 청크 결과 묶음. fetcher는 어댑터 결과를 해석하지 않고 담기만 한다 — 병합·판정은 assembler 한 곳에서.
// elapsed는 청크 개별 소요시간의 합이 아니라 이 공급사를 기다린 벽시계 시간이다(청크는 병렬이라 합은 의미가 없다).
public record SupplierFetchOutcome(SupplierId supplier, List<SupplierResult<AvailabilityResult>> results, Duration elapsed) {

	public SupplierFetchOutcome {
		results = List.copyOf(results);
	}
}
