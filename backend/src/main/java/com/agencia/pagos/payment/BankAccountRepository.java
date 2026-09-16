package com.agencia.pagos.payment;

import com.agencia.pagos.payment.BankAccount;
import com.agencia.pagos.shared.money.Currency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    List<BankAccount> findByActiveTrueOrderByDisplayOrderAscIdAsc();

    List<BankAccount> findByActiveTrueAndCurrencyOrderByDisplayOrderAscIdAsc(Currency currency);

    List<BankAccount> findAllByOrderByDisplayOrderAscIdAsc();

    boolean existsByAliasIgnoreCase(String alias);

    boolean existsByCbu(String cbu);
}
