package com.agencia.pagos.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "payment_batches")
public class PaymentBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal reportedAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Currency paymentCurrency;

    @Column(precision = 10, scale = 2)
    private BigDecimal exchangeRate;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amountInTripCurrency;

    @Column(nullable = false)
    private LocalDate reportedPaymentDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod;

    @ManyToOne
    @JoinColumn(name = "bank_account_id")
    private BankAccount bankAccount;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String fileKey;

    @OneToMany(mappedBy = "batch")
    private List<PaymentReceipt> receipts = new ArrayList<>();

    @PrePersist
    void onPersist() {
        if (paymentCurrency == null) {
            paymentCurrency = Currency.ARS;
        }
        if (amountInTripCurrency == null) {
            amountInTripCurrency = reportedAmount;
        }
        if (fileKey == null) {
            fileKey = "";
        }
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getReportedAmount() {
        return reportedAmount;
    }

    public void setReportedAmount(BigDecimal reportedAmount) {
        this.reportedAmount = reportedAmount;
    }

    public Currency getPaymentCurrency() {
        return paymentCurrency;
    }

    public void setPaymentCurrency(Currency paymentCurrency) {
        this.paymentCurrency = paymentCurrency;
    }

    public BigDecimal getExchangeRate() {
        return exchangeRate;
    }

    public void setExchangeRate(BigDecimal exchangeRate) {
        this.exchangeRate = exchangeRate;
    }

    public BigDecimal getAmountInTripCurrency() {
        return amountInTripCurrency;
    }

    public void setAmountInTripCurrency(BigDecimal amountInTripCurrency) {
        this.amountInTripCurrency = amountInTripCurrency;
    }

    public LocalDate getReportedPaymentDate() {
        return reportedPaymentDate;
    }

    public void setReportedPaymentDate(LocalDate reportedPaymentDate) {
        this.reportedPaymentDate = reportedPaymentDate;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public void setPaymentMethod(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    public BankAccount getBankAccount() {
        return bankAccount;
    }

    public void setBankAccount(BankAccount bankAccount) {
        this.bankAccount = bankAccount;
    }

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String fileKey) {
        this.fileKey = fileKey;
    }

    public List<PaymentReceipt> getReceipts() {
        return receipts;
    }

    public void setReceipts(List<PaymentReceipt> receipts) {
        this.receipts = receipts;
    }
}
