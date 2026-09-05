package com.demo.stayintegration.supplier.adapter.support;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import org.springframework.core.codec.CodecException;
import org.springframework.http.HttpStatusCode;

import com.demo.stayintegration.supplier.port.FailureKind;

import io.netty.handler.timeout.ReadTimeoutException;

public final class FailureClassifier {

	// 원인 체인을 끝까지 따라가되, 자기 자신을 cause로 가진 예외에서 무한 루프에 빠지지 않게 상한을 둔다
	private static final int MAX_CAUSE_DEPTH = 10;

	private FailureClassifier() {}

	// HTTP 상태로 실패를 알리는 공급사용. 400은 우리 요청 문제, 그 외 4xx는 계약에 없는 것이라 UNEXPECTED — 재시도하지 않는다.
	public static FailureKind fromStatus(HttpStatusCode status) {
		if (status.is5xxServerError()) {
			return FailureKind.SERVER_ERROR;
		}
		return switch (status.value()) {
			case 429 -> FailureKind.RATE_LIMITED;
			case 401 -> FailureKind.UNAUTHORIZED;
			case 400 -> FailureKind.BAD_REQUEST;
			default -> FailureKind.UNEXPECTED;
		};
	}

	// WebClient는 I/O 예외를 WebClientRequestException으로 감싸므로 cause를 따라가며 본다.
	// 연결 타임아웃(ConnectTimeoutException extends ConnectException)은 응답이 아니라 연결이 안 된 것이라 CONNECTION_FAILED.
	public static FailureKind fromException(Throwable error) {
		Throwable current = error;
		for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++, current = current.getCause()) {
			if (current instanceof TimeoutException || current instanceof ReadTimeoutException) {
				return FailureKind.TIMEOUT;
			}
			if (current instanceof ConnectException || current instanceof UnknownHostException) {
				return FailureKind.CONNECTION_FAILED;
			}
			if (current instanceof CodecException) {
				return FailureKind.UNEXPECTED;
			}
			if (current instanceof IOException) {
				return FailureKind.CONNECTION_FAILED;
			}
		}
		return FailureKind.UNEXPECTED;
	}

	// 로그와 리포트에 담을 한 줄. 래퍼 예외의 장황한 메시지보다 근본 원인이 읽기 쉽다.
	public static String describe(Throwable error) {
		Throwable root = error;
		for (int depth = 0; root.getCause() != null && depth < MAX_CAUSE_DEPTH; depth++) {
			root = root.getCause();
		}
		return root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
	}
}
