package com.agencia.pagos;

import org.springframework.boot.SpringApplication;

public class TestPagosApplication {

	public static void main(String[] args) {
		SpringApplication.from(PagosApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
