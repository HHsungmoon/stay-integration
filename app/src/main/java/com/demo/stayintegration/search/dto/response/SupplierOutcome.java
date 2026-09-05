package com.demo.stayintegration.search.dto.response;

// offers + unmapped + rejected = 공급사가 준 항목 수. offers는 응답 items에 실린 것만 센다.
// failure에는 첫 실패의 kind·detail만 담는다 — 청크가 여럿이면 원인이 다를 수 있지만 failedCalls가 개수를 말한다.
// skippedCalls: 호출하지 않은 청크 수(서킷 열림·물어볼 숙소 없음). 요청 도중 서킷이 열리면 앞 청크는 calls에, 뒤 청크는 여기에 남아
// calls + skippedCalls가 계획했던 청크 수가 된다 — 이 값이 없으면 건너뛴 청크가 응답에서 흔적 없이 사라진다.
public record SupplierOutcome(
		String supplier,
		Status status,
		int calls, int failedCalls, int skippedCalls,
		long elapsedMs,
		int offers, int rejected, int unmapped,
		FailureInfo failure) {

	// SKIPPED는 실패가 아니다 — 서킷의 보호 동작이거나 물어볼 숙소가 없는 카탈로그의 상태다.
	public enum Status { SUCCESS, PARTIAL, FAILED, SKIPPED }

	// retryable을 노출하는 이유: 클라이언트가 "잠시 후 재시도" 안내를 낼 수 있는 유일한 근거다. kind 문자열 매칭을 시키는 것보다 낫다.
	public record FailureInfo(String kind, boolean retryable, String detail) {}
}
