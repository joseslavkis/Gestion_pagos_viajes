package com.agencia.pagos.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.agencia.pagos.entities.Role;
import com.agencia.pagos.entities.user.User;
import com.agencia.pagos.repositories.UserRepository;

@Configuration
public class DefaultAdminSeeder {

    @Bean
    CommandLineRunner seedDefaultAdmin(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        return args -> {
            String adminEmail = System.getenv().getOrDefault("DEFAULT_ADMIN_EMAIL", "admin@agencia.com");
            String adminPassword = System.getenv().getOrDefault("DEFAULT_ADMIN_PASSWORD", "Admin1234!");

            if (userRepository.findByEmail(adminEmail).isPresent()) {
                return;
            }

            User admin = new User(
                    "Admin",
                    passwordEncoder.encode(adminPassword),
                    adminEmail,
                    "Agencia",
                    Role.ADMIN
            );

            userRepository.save(admin);
        };
    }
}