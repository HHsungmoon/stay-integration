package com.demo.stayintegration.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.demo.stayintegration.catalog.service.SyncAlreadyRunningException;

// 에러 응답만 여기서 통일한다. 성공 응답은 envelope 없이 각 API의 형태를 그대로 쓴다(CLAUDE.md 응답 형태).
@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(SyncAlreadyRunningException.class)
	public ResponseEntity<ErrorResponse> handleSyncAlreadyRunning(SyncAlreadyRunningException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("SYNC_ALREADY_RUNNING", e.getMessage()));
	}
}
