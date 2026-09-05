package com.demo.stayintegration.supplier.port;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class SupplierRegistry {

	private final Map<SupplierId, SupplierAdapter> adapters;

	// ObjectProvider인 이유: 어댑터가 하나도 없어도(이 단계) 컨텍스트가 떠야 한다. List<T> 주입은 0개면 실패한다.
	public SupplierRegistry(ObjectProvider<SupplierAdapter> provider) {
		Map<SupplierId, SupplierAdapter> byId = new LinkedHashMap<>();
		for (SupplierAdapter adapter : provider.orderedStream().toList()) {
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
