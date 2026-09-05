package com.demo.mocksupplier.common;

// 공급사 중립적인 거절 사유. A는 HTTP 상태 + error 코드로, B는 200 + resultCode로 각자 번역한다.
public enum RequestError {
	UNAUTHORIZED,
	INVALID_PARAMETER,
	INVALID_DATE_RANGE,
	TOO_MANY_CODES
}
