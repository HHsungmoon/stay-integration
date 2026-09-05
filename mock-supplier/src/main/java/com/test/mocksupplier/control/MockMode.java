package com.test.mocksupplier.control;

public enum MockMode {
	NORMAL, ERROR, NO_RESPONSE, DELAY;

	// 제어 API와 curl 예시는 kebab-case(no-response)를 쓴다. 그 표기를 그대로 받는다.
	public static MockMode parse(String value) {
		return valueOf(value.trim().toUpperCase().replace('-', '_'));
	}

	public String label() {
		return name().toLowerCase().replace('_', '-');
	}
}
