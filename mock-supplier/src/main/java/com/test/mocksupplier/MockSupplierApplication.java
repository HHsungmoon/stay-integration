package com.test.mocksupplier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// 본체(:app)와 별도 프로세스로 띄운다. 같은 프로세스에 두면 본체가 자기 자신을 HTTP로
// 호출하게 되어, 무응답을 재현했을 때 공급사 지연인지 톰캣 스레드 고갈인지 구분할 수 없다.
@ConfigurationPropertiesScan
@SpringBootApplication
public class MockSupplierApplication {

	public static void main(String[] args) {
		SpringApplication.run(MockSupplierApplication.class, args);
	}
}
