package com.demo.stayintegration.supplier.port;

// 재시도 정책의 기반이 되므로 지금 정의한다. 세부(타임아웃·5xx 등)는 어댑터가 detail에 담는다.
public enum FailureKind {
	TRANSIENT,       // 타임아웃, 5xx, 일시 장애, 호출 한도 초과 — 재시도 가치 있음
	PERMANENT,       // 인증 실패, 잘못된 요청, 코드 50개 초과 — 재시도 무의미
	NORMALIZATION    // 응답은 왔으나 표준 모델로 변환 불가
}
