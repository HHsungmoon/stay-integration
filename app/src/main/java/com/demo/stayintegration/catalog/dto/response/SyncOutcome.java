package com.demo.stayintegration.catalog.dto.response;

// 한 공급사 동기화의 집계. DB upsert(ON CONFLICT)를 쓰지 않고 읽기→비교→저장으로 가는 이유가 이 숫자들이다.
public record SyncOutcome(int created, int updated, int reactivated, int deactivated) {

	public static SyncOutcome empty() {
		return new SyncOutcome(0, 0, 0, 0);
	}
}
