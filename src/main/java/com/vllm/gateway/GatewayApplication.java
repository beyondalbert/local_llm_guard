package com.vllm.gateway;

import com.vllm.gateway.service.AdminService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.file.Files;
import java.nio.file.Paths;

@SpringBootApplication
@EnableScheduling
public class GatewayApplication {

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get("data"));
        SpringApplication.run(GatewayApplication.class, args);
    }

    @Bean
    public CommandLineRunner initAdmin(AdminService adminService) {
        return args -> adminService.ensureDefaultAdmin();
    }
}
