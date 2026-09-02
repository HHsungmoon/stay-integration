package com.test.mocksupplier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 공급사 API를 흉내내는 독립 서버.
 *
 * <p>본체(:app)와 별도 프로세스로 뜬다. 같은 프로세스에 두면 본체가 자기 자신을
 * HTTP로 호출하게 되어, 무응답 상황을 재현할 때 그것이 공급사 지연 때문인지
 * 스레드 고갈 때문인지 구분할 수 없게 된다.
 */
@SpringBootApplication
public class MockSupplierApplication {

	public static void main(String[] args) {
		SpringApplication.run(MockSupplierApplication.class, args);
	}
}
