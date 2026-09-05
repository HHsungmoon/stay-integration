package com.demo.stayintegration.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 엔티티·서비스가 Instant.now()를 직접 부르지 않고 Clock을 받는다. 테스트가 시각을 고정할 수 있다.
@Configuration
public class ClockConfig {

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
