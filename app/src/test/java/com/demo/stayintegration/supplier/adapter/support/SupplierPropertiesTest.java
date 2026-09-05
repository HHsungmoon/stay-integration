package com.demo.stayintegration.supplier.adapter.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.demo.stayintegration.supplier.adapter.support.SupplierProperties.Endpoint;
import com.demo.stayintegration.supplier.port.SupplierId;

class SupplierPropertiesTest {

	static final Endpoint VALID = new Endpoint("http://localhost:9090", "key", Duration.ofMillis(500), Duration.ofSeconds(2));

	@Test
	void missingSupplierFailsFastWithTheConfigKeyInTheMessage() {
		SupplierProperties properties = new SupplierProperties(Map.of("a", VALID));

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
	void nullEndpointsMapIsTreatedAsEmpty() {
		assertThat(new SupplierProperties(null).endpoints()).isEmpty();
	}
}
