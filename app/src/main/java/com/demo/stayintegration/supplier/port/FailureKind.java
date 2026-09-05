package com.demo.stayintegration.supplier.port;

// 원인 enum 하나에 retryable()을 둔다. 재시도 정책은 이 메서드만 보고, 관측성 outcome 태그는 name()에서 파생된다.
// 재시도 여부만 말하는 3분류(TRANSIENT/PERMANENT/NORMALIZATION)로는 로그와 지표에서 "왜 실패했나"를 읽을 수 없었다.
public enum FailureKind {
	TIMEOUT(true),                // 응답 타임아웃, 오케스트레이션 데드라인
	CONNECTION_FAILED(true),      // 연결 거부·리셋·DNS 실패 — 연결 타임아웃도 여기(연결 자체가 안 된 것)
	SERVER_ERROR(true),           // 5xx, 본문 코드 E5xx
	RATE_LIMITED(true),           // 429, E429
	UNAUTHORIZED(false),          // 401, E401
	BAD_REQUEST(false),           // 400, E400, 어댑터의 50개 사전 거절
	UNEXPECTED(false),            // 그 외 4xx, 파싱 불가 본문, 모르는 코드, 봉투 이상, 어댑터 예외(계약 위반)
	NORMALIZATION_FAILED(false);  // 응답은 왔으나 전체를 표준 모델로 바꿀 수 없음

	private final boolean retryable;

	FailureKind(boolean retryable) {
		this.retryable = retryable;
	}

	// 모르는 것은 재시도하지 않는다. 재시도 예산은 데드라인 안에 갇혀 있어, 이해하지 못하는 실패에 쓰면
	// 이해하는 실패를 재시도할 기회를 잃는다.
	public boolean retryable() {
		return retryable;
	}
}
