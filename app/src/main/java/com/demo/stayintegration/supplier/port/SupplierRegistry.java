package com.demo.stayintegration.supplier.port;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SupplierRegistry {

	private final Map<SupplierId, SupplierAdapter> adapters;

	// ObjectProvider인 이유: 어댑터가 하나도 없어도 컨텍스트가 떠야 한다. List<T> 주입은 0개면 실패한다.
	// @Autowired는 생성자가 둘이라 Spring이 쓸 것을 지정하는 용도다.
	@Autowired
	public SupplierRegistry(ObjectProvider<SupplierAdapter> provider) {
		this(provider.orderedStream().toList());
	}

	// 컴포넌트 스캔이 아닌 명시적 목록으로도 만들 수 있어야 한다 — 실제 어댑터가 빈으로 있는 컨텍스트에서
	// 테스트가 stub만 담은 레지스트리로 갈아 끼우는 경로다.
	public SupplierRegistry(List<SupplierAdapter> adapterList) {
		Map<SupplierId, SupplierAdapter> byId = new LinkedHashMap<>();
		for (SupplierAdapter adapter : adapterList) {
			// 같은 공급사 어댑터가 둘 등록되는 설정 실수를 조용히 넘기지 않는다
			if (byId.putIfAbsent(adapter.id(), adapter) != null) {
				throw new IllegalStateException("duplicate supplier adapter: " + adapter.id());
			}
		}
		this.adapters = Collections.unmodifiableMap(byId);
	}

	public Collection<SupplierAdapter> all() {
		return adapters.values();
	}

	public Optional<SupplierAdapter> find(SupplierId id) {
		return Optional.ofNullable(adapters.get(id));
	}
}
