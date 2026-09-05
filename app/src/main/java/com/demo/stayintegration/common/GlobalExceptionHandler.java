package com.demo.stayintegration.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.demo.stayintegration.catalog.service.SyncAlreadyRunningException;
import com.demo.stayintegration.search.dto.request.InvalidSearchRequestException;

// 에러 응답만 여기서 통일한다. 성공 응답은 envelope 없이 각 API의 형태를 그대로 쓴다(CLAUDE.md 응답 형태).
// Boot의 ProblemDetail(RFC 9457)로 바꾸지 않는다 — 기존 ErrorResponse 형태가 있고, 형태를 바꾸는 것은 기능이 아니다.
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(SyncAlreadyRunningException.class)
	public ResponseEntity<ErrorResponse> handleSyncAlreadyRunning(SyncAlreadyRunningException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("SYNC_ALREADY_RUNNING", e.getMessage()));
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
		return badRequest("MISSING_PARAMETER", e.getParameterName() + " is required");
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return badRequest("INVALID_PARAMETER", e.getName() + " has an invalid value: " + e.getValue());
	}

	// code는 예외가 든다(INVALID_PARAMETER / INVALID_DATE_RANGE / TOO_MANY_NIGHTS) — 어느 규칙인지는 검증한 쪽이 안다
	@ExceptionHandler(InvalidSearchRequestException.class)
	public ResponseEntity<ErrorResponse> handleInvalidSearchRequest(InvalidSearchRequestException e) {
		return badRequest(e.code(), e.getMessage());
	}

	private static ResponseEntity<ErrorResponse> badRequest(String code, String message) {
		return ResponseEntity.badRequest().body(new ErrorResponse(code, message));
	}
}
