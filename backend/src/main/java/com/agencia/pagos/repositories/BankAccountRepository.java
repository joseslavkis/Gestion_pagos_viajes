package com.agencia.pagos.repositories;

import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    List<BankAccount> findByActiveTrueOrderByDisplayOrderAscIdAsc();

    List<BankAccount> findByActiveTrueAndCurrencyOrderByDisplayOrderAscIdAsc(Currency currency);

    List<BankAccount> findAllByOrderByDisplayOrderAscIdAsc();
}
