package com.mosquizto.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@EnableCaching
@SpringBootApplication
public class MosquiztoBackendApplication {

	public static void main(String[] args) {
		if (java.util.Arrays.asList(args).contains("--prepare-model")) {
			System.exit(com.mosquizto.sync.PrepareModel.run(System.getenv()));
			return;
		}
		if (java.util.Arrays.asList(args).contains("--sync")) {
			System.exit(com.mosquizto.sync.SyncApplication.run(System.getenv()));
			return;
		}
		SpringApplication.run(MosquiztoBackendApplication.class, args);
	}

}
