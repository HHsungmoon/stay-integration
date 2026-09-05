package com.demo.stayintegration.supplier.port;

// enum이 아닌 이유: "신규 공급사 추가 시 고칠 것 = 어댑터 1개 + 설정 + 레지스트리 등록"이라고
// 못 박았다. enum이면 상수 추가가 그 목록에 붙어 목록이 거짓이 된다. 값은 yaml에서 오는 런타임 값이다.
public record SupplierId(String value) {

	public SupplierId {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("supplier id must not be blank");
		}
	}

	@Override
	public String toString() {
		return value;
	}
}
