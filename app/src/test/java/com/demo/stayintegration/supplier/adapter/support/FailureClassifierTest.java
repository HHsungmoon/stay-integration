package com.demo.stayintegration.supplier.adapter.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import com.demo.stayintegration.supplier.port.FailureKind;

import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;

class FailureClassifierTest {

	@Test
	void httpStatusMatrix() {
		assertThat(FailureClassifier.fromStatus(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(FailureClassifier.fromStatus(HttpStatus.INTERNAL_SERVER_ERROR)).isEqualTo(FailureKind.SERVER_ERROR);
		assertThat(FailureClassifier.fromStatus(HttpStatus.TOO_MANY_REQUESTS)).isEqualTo(FailureKind.RATE_LIMITED);
		assertThat(FailureClassifier.fromStatus(HttpStatus.UNAUTHORIZED)).isEqualTo(FailureKind.UNAUTHORIZED);
		assertThat(FailureClassifier.fromStatus(HttpStatus.BAD_REQUEST)).isEqualTo(FailureKind.BAD_REQUEST);
		// 계약에 없는 상태는 재시도하지 않는다
		assertThat(FailureClassifier.fromStatus(HttpStatus.NOT_FOUND)).isEqualTo(FailureKind.UNEXPECTED);
		assertThat(FailureClassifier.fromStatus(HttpStatus.FORBIDDEN)).isEqualTo(FailureKind.UNEXPECTED);
		assertThat(FailureClassifier.fromStatus(HttpStatus.MOVED_PERMANENTLY)).isEqualTo(FailureKind.UNEXPECTED);
	}

	@Test
	void reactorTimeoutAndNettyReadTimeoutAreTimeouts() {
		assertThat(FailureClassifier.fromException(new TimeoutException("Did not observe any item"))).isEqualTo(FailureKind.TIMEOUT);
		assertThat(FailureClassifier.fromException(wrapped(ReadTimeoutException.INSTANCE))).isEqualTo(FailureKind.TIMEOUT);
	}

	@Test
	void connectionProblemsAreConnectionFailures() {
		assertThat(FailureClassifier.fromException(wrapped(new ConnectException("Connection refused")))).isEqualTo(FailureKind.CONNECTION_FAILED);
		// 연결 타임아웃은 응답이 아니라 연결이 안 된 것 — ConnectException의 하위 타입이라 같은 분류로 떨어진다
		assertThat(FailureClassifier.fromException(wrapped(new ConnectTimeoutException("connection timed out")))).isEqualTo(FailureKind.CONNECTION_FAILED);
		assertThat(FailureClassifier.fromException(wrapped(new UnknownHostException("supplier-a.invalid")))).isEqualTo(FailureKind.CONNECTION_FAILED);
		assertThat(FailureClassifier.fromException(wrapped(new IOException("Connection reset by peer")))).isEqualTo(FailureKind.CONNECTION_FAILED);
	}

	@Test
	void unparseableBodyAndUnknownExceptionsAreUnexpected() {
		assertThat(FailureClassifier.fromException(new DecodingException("JSON decoding error"))).isEqualTo(FailureKind.UNEXPECTED);
		assertThat(FailureClassifier.fromException(new IllegalStateException("boom"))).isEqualTo(FailureKind.UNEXPECTED);
	}

	@Test
	void selfReferencingCauseChainTerminates() {
		RuntimeException loop = new RuntimeException("loop") {
			@Override public synchronized Throwable getCause() { return this; }
		};
		assertThat(FailureClassifier.fromException(loop)).isEqualTo(FailureKind.UNEXPECTED);
		assertThat(FailureClassifier.describe(loop)).contains("loop");
	}

	@Test
	void describeUsesTheRootCause() {
		assertThat(FailureClassifier.describe(wrapped(new ConnectException("Connection refused: localhost/127.0.0.1:9090"))))
				.isEqualTo("ConnectException: Connection refused: localhost/127.0.0.1:9090");
	}

	private static WebClientRequestException wrapped(Throwable cause) {
		return new WebClientRequestException(cause, HttpMethod.GET, URI.create("http://localhost:9090/a/v1/hotels"), new HttpHeaders());
	}
}
