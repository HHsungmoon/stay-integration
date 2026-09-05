package com.demo.stayintegration.supplier.adapter.support;

// 정규화 중 항목 하나의 결함을 그 항목의 처리만 중단시키는 신호. 어댑터 밖으로 나가지 않고 normalizer 안에서 잡혀
// NormalizationIssue가 된다. 스택 트레이스를 만들지 않는 이유: 응답 항목마다 던질 수 있는 값이라 비용을 내지 않는다.
public final class ItemDefect extends RuntimeException {

	public ItemDefect(String reason) {
		super(reason, null, false, false);
	}
}
