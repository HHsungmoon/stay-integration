package com.demo.stayintegration.supplier.adapter.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.demo.stayintegration.supplier.adapter.support.SupplierProperties.Circuit;
import com.demo.stayintegration.supplier.adapter.support.SupplierProperties.Endpoint;
import com.demo.stayintegration.supplier.port.SupplierId;

class SupplierPropertiesTest {

	static final Endpoint VALID = new Endpoint("http://localhost:9090", "key", Duration.ofMillis(500), Duration.ofSeconds(2));
	static final Circuit CIRCUIT = new Circuit(20, 10, 50f, Duration.ofSeconds(10), 3);

	@Test
	void missingSupplierFailsFastWithTheConfigKeyInTheMessage() {
		SupplierProperties properties = new SupplierProperties(Map.of("a", VALID), CIRCUIT);

		assertThat(properties.endpointOf(new SupplierId("a"))).isEqualTo(VALID);
		assertThatThrownBy(() -> properties.endpointOf(new SupplierId("c")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("supplier.endpoints.c");
	}

	@Test
	void endpointRejectsMissingOrNonPositiveValues() {
		assertThatThrownBy(() -> new Endpoint(" ", "key", Duration.ofMillis(500), Duration.ofSeconds(2))).hasMessageContaining("base-url");
		assertThatThrownBy(() -> new Endpoint("http://x", null, Duration.ofMillis(500), Duration.ofSeconds(2))).hasMessageContaining("api-key");
		assertThatThrownBy(() -> new Endpoint("http://x", "key", Duration.ZERO, Duration.ofSeconds(2))).hasMessageContaining("connect-timeout");
		assertThatThrownBy(() -> new Endpoint("http://x", "key", Duration.ofMillis(500), null)).hasMessageContaining("response-timeout");
	}

	@Test
	void circuitIsRequiredAndValidated() {
		// 임계값은 설계값이라 yaml에 근거와 함께 있어야 한다 — 누락은 기동 실패
		assertThatThrownBy(() -> new SupplierProperties(Map.of("a", VALID), null)).hasMessageContaining("supplier.circuit");
		assertThatThrownBy(() -> new Circuit(0, 10, 50f, Duration.ofSeconds(10), 3)).hasMessageContaining("sliding-window-size");
		assertThatThrownBy(() -> new Circuit(20, 0, 50f, Duration.ofSeconds(10), 3)).hasMessageContaining("minimum-number-of-calls");
		assertThatThrownBy(() -> new Circuit(20, 10, 0f, Duration.ofSeconds(10), 3)).hasMessageContaining("failure-rate-threshold");
		assertThatThrownBy(() -> new Circuit(20, 10, 101f, Duration.ofSeconds(10), 3)).hasMessageContaining("failure-rate-threshold");
		assertThatThrownBy(() -> new Circuit(20, 10, 50f, Duration.ZERO, 3)).hasMessageContaining("wait-duration-in-open-state");
		assertThatThrownBy(() -> new Circuit(20, 10, 50f, Duration.ofSeconds(10), 0)).hasMessageContaining("permitted-number-of-calls");
	}

	@Test
	void nullEndpointsMapIsTreatedAsEmpty() {
		assertThat(new SupplierProperties(null, CIRCUIT).endpoints()).isEmpty();
	}
}
