package com.agencia.pagos.services;

import com.agencia.pagos.dtos.request.BankAccountActiveDTO;
import com.agencia.pagos.dtos.request.BankAccountCreateDTO;
import com.agencia.pagos.dtos.request.BankAccountUpdateDTO;
import com.agencia.pagos.dtos.response.BankAccountDTO;
import com.agencia.pagos.entities.BankAccount;
import com.agencia.pagos.entities.Currency;
import com.agencia.pagos.repositories.BankAccountRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class BankAccountService {

    private final BankAccountRepository bankAccountRepository;

    @Autowired
    public BankAccountService(BankAccountRepository bankAccountRepository) {
        this.bankAccountRepository = bankAccountRepository;
    }

    @Transactional(readOnly = true)
    public List<BankAccountDTO> getActiveAccounts(Currency currency) {
        List<BankAccount> accounts = currency == null
                ? bankAccountRepository.findByActiveTrueOrderByDisplayOrderAscIdAsc()
                : bankAccountRepository.findByActiveTrueAndCurrencyOrderByDisplayOrderAscIdAsc(currency);
        return accounts.stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public List<BankAccountDTO> getAllAccountsForAdmin() {
        return bankAccountRepository.findAllByOrderByDisplayOrderAscIdAsc().stream()
                .map(this::toDTO)
                .toList();
    }

    public BankAccountDTO create(BankAccountCreateDTO dto) {
        BankAccount account = BankAccount.builder()
                .bankName(dto.bankName().trim())
                .accountLabel(dto.accountLabel().trim())
                .accountHolder(dto.accountHolder().trim())
                .accountNumber(dto.accountNumber().trim())
                .taxId(dto.taxId().trim())
                .cbu(dto.cbu().trim())
                .alias(dto.alias().trim())
                .currency(dto.currency())
                .active(true)
                .displayOrder(dto.displayOrder() == null ? 0 : dto.displayOrder())
                .build();
        return toDTO(bankAccountRepository.save(account));
    }

    public BankAccountDTO update(Long id, BankAccountUpdateDTO dto) {
        BankAccount account = bankAccountRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("BankAccount not found with id " + id));

        account.setBankName(dto.bankName().trim());
        account.setAccountLabel(dto.accountLabel().trim());
        account.setAccountHolder(dto.accountHolder().trim());
        account.setAccountNumber(dto.accountNumber().trim());
        account.setTaxId(dto.taxId().trim());
        account.setCbu(dto.cbu().trim());
        account.setAlias(dto.alias().trim());
        account.setCurrency(dto.currency());
        account.setDisplayOrder(dto.displayOrder() == null ? 0 : dto.displayOrder());

        return toDTO(bankAccountRepository.save(account));
    }

    public BankAccountDTO updateActive(Long id, BankAccountActiveDTO dto) {
        BankAccount account = bankAccountRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("BankAccount not found with id " + id));
        account.setActive(Boolean.TRUE.equals(dto.active()));
        return toDTO(bankAccountRepository.save(account));
    }

    private BankAccountDTO toDTO(BankAccount account) {
        return new BankAccountDTO(
                account.getId(),
                account.getBankName(),
                account.getAccountLabel(),
                account.getAccountHolder(),
                account.getAccountNumber(),
                account.getTaxId(),
                account.getCbu(),
                account.getAlias(),
                account.getCurrency(),
                account.isActive(),
                account.getDisplayOrder()
        );
    }
}
