package com.demo.mocksupplier.control;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.demo.mocksupplier.common.MockProperties;

@RestController
@RequestMapping("/control")
public class MockControlController {

	private final MockModeRegistry registry;
	private final MockProperties properties;

	public MockControlController(MockModeRegistry registry, MockProperties properties) {
		this.registry = registry;
		this.properties = properties;
	}

	@GetMapping
	public Map<String, String> current() {
		return registry.snapshot();
	}

	@PostMapping("/{supplier}/mode")
	public Map<String, String> setMode(
			@PathVariable String supplier,
			@RequestParam String value,
			@RequestParam(required = false) String api,
			@RequestParam(required = false) Long delayMs) {

		if (!MockModeRegistry.SUPPLIERS.contains(supplier)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "supplier must be one of " + MockModeRegistry.SUPPLIERS);
		}
		if (api != null && !MockModeRegistry.APIS.contains(api)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "api must be one of " + MockModeRegistry.APIS);
		}
		MockMode mode;
		try {
			mode = MockMode.parse(value);
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value must be normal|error|no-response|delay");
		}
		registry.set(supplier, api, mode, delayMs != null ? delayMs : properties.delayDefaultMs());
		return registry.snapshot();
	}

	// 공급사마다 부록 예시 뒤에 합성 숙소 count개를 붙인다. 카탈로그 동기화를 다시 돌려야 본체가 알게 된다.
	@PostMapping("/catalog/synthetic")
	public Map<String, String> setSyntheticHotels(@RequestParam int count) {
		if (count < 0 || count > 5_000) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "count must be within 0..5000");
		}
		registry.setSyntheticHotels(count);
		return registry.snapshot();
	}

	@PostMapping("/reset")
	public Map<String, String> reset() {
		registry.reset();
		return registry.snapshot();
	}
}
