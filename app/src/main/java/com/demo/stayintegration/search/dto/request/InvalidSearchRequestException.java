package com.demo.stayintegration.search.dto.request;

// code는 GlobalExceptionHandler가 400 응답의 code로 그대로 쓴다. 어느 규칙에 걸렸는지가 클라이언트에게 필요한 정보다.
public class InvalidSearchRequestException extends RuntimeException {

	private final String code;

	public InvalidSearchRequestException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String code() {
		return code;
	}
}
