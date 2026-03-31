package com.agencia.pagos.config;

import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.repositories.BankAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;
import java.util.Optional;

@Configuration
public class DefaultBankAccountsSeeder {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultBankAccountsSeeder.class);
    private static final int MAX_DEFAULT_BANK_ACCOUNTS = 10;

    @Bean
    CommandLineRunner seedDefaultBankAccounts(BankAccountRepository bankAccountRepository) {
        return args -> {
            if (!defaultBankAccountsEnabled()) {
                LOGGER.info("Skipping default bank accounts seeding because DEFAULT_BANK_ACCOUNTS_ENABLED=false");
                return;
            }

            for (int index = 1; index <= MAX_DEFAULT_BANK_ACCOUNTS; index++) {
                loadSeedConfig(index).ifPresent(config -> seedAccount(bankAccountRepository, config));
            }
        };
    }

    private void seedAccount(BankAccountRepository bankAccountRepository, BankAccountSeedConfig config) {
        if (bankAccountRepository.existsByAliasIgnoreCase(config.alias())) {
            LOGGER.info("Skipping default bank account {} because alias {} already exists", config.index(), config.alias());
            return;
        }

        if (bankAccountRepository.existsByCbu(config.cbu())) {
            LOGGER.info("Skipping default bank account {} because CBU {} already exists", config.index(), config.cbu());
            return;
        }

        BankAccount account = BankAccount.builder()
                .bankName(config.bankName())
                .accountLabel(config.accountLabel())
                .accountHolder(config.accountHolder())
                .accountNumber(config.accountNumber())
                .taxId(config.taxId())
                .cbu(config.cbu())
                .alias(config.alias())
                .currency(config.currency())
                .active(true)
                .displayOrder(config.displayOrder())
                .build();

        bankAccountRepository.save(account);
        LOGGER.info(
                "Seeded default bank account {} ({}, {})",
                config.index(),
                config.bankName(),
                config.currency()
        );
    }

    private Optional<BankAccountSeedConfig> loadSeedConfig(int index) {
        String prefix = "DEFAULT_BANK_ACCOUNT_" + index + "_";

        String bankName = env(prefix + "BANK_NAME");
        String accountLabel = env(prefix + "ACCOUNT_LABEL");
        String accountHolder = env(prefix + "ACCOUNT_HOLDER");
        String accountNumber = env(prefix + "ACCOUNT_NUMBER");
        String taxId = env(prefix + "TAX_ID");
        String cbu = env(prefix + "CBU");
        String alias = env(prefix + "ALIAS");
        String currencyValue = env(prefix + "CURRENCY");
        String displayOrderValue = env(prefix + "DISPLAY_ORDER");

        if (allBlank(
                bankName,
                accountLabel,
                accountHolder,
                accountNumber,
                taxId,
                cbu,
                alias,
                currencyValue,
                displayOrderValue
        )) {
            return Optional.empty();
        }

        if (anyBlank(bankName, accountLabel, accountHolder, accountNumber, taxId, cbu, alias, currencyValue)) {
            LOGGER.warn(
                    "Skipping default bank account {} because its configuration is incomplete. Check {}* variables",
                    index,
                    prefix
            );
            return Optional.empty();
        }

        Currency currency;
        try {
            currency = Currency.valueOf(currencyValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            LOGGER.warn(
                    "Skipping default bank account {} because currency {} is invalid. Expected one of {}",
                    index,
                    currencyValue,
                    java.util.Arrays.toString(Currency.values())
            );
            return Optional.empty();
        }

        Integer displayOrder = index;
        if (displayOrderValue != null) {
            try {
                displayOrder = Integer.valueOf(displayOrderValue);
            } catch (NumberFormatException exception) {
                LOGGER.warn(
                        "Skipping default bank account {} because display order {} is invalid",
                        index,
                        displayOrderValue
                );
                return Optional.empty();
            }
        }

        return Optional.of(new BankAccountSeedConfig(
                index,
                bankName,
                accountLabel,
                accountHolder,
                accountNumber,
                taxId,
                cbu,
                alias,
                currency,
                displayOrder
        ));
    }

    private boolean defaultBankAccountsEnabled() {
        return Boolean.parseBoolean(System.getenv().getOrDefault("DEFAULT_BANK_ACCOUNTS_ENABLED", "true"));
    }

    private String env(String key) {
        String value = System.getenv(key);
        if (value == null) {
            return null;
        }

        String trimmed = stripOptionalQuotes(value.trim());
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String stripOptionalQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }

    private boolean anyBlank(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private boolean allBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private record BankAccountSeedConfig(
            int index,
            String bankName,
            String accountLabel,
            String accountHolder,
            String accountNumber,
            String taxId,
            String cbu,
            String alias,
            Currency currency,
            Integer displayOrder
    ) {
    }
}
