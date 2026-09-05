package com.demo.stayintegration.supplier.port;

import java.time.Duration;

// 실패를 예외가 아니라 값으로 다룬다. 예외로 전파하면 병렬 스트림 하나가 죽을 때 전체가 죽어
// 부분 실패 허용이 구조적으로 불가능해진다. elapsed를 성공·실패 모두 담는 이유: 관측성 지표가 여기서 파생된다.
public sealed interface SupplierResult<T> {

	SupplierId supplier();

	Duration elapsed();

	record Success<T>(SupplierId supplier, T value, Duration elapsed) implements SupplierResult<T> {}

	record Failure<T>(SupplierId supplier, FailureKind kind, String detail, Duration elapsed) implements SupplierResult<T> {}

	// 호출조차 하지 않은 경우(서킷 열림 등). "실패"와 다른 사건이라 따로 둔다.
	record Skipped<T>(SupplierId supplier, String reason, Duration elapsed) implements SupplierResult<T> {}
}
