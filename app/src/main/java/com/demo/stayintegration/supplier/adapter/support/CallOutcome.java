package com.demo.stayintegration.supplier.adapter.support;

import com.demo.stayintegration.supplier.port.FailureKind;

// 파이프라인 안에서 판정 결과를 나르는 값. SupplierResult와 따로 두는 이유: 소요시간은 파이프라인이 마지막에 찍는다 —
// 상태 코드·본문 코드로 실패를 판정하는 시점에는 아직 elapsed를 모른다.
public sealed interface CallOutcome<T> {

	record Ok<T>(T value) implements CallOutcome<T> {}

	record Failed<T>(FailureKind kind, String detail) implements CallOutcome<T> {}

	static <T> CallOutcome<T> ok(T value) {
		return new Ok<>(value);
	}

	static <T> CallOutcome<T> failed(FailureKind kind, String detail) {
		return new Failed<>(kind, detail);
	}
}
