package com.agencia.pagos.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import com.agencia.pagos.shared.money.Currency;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(
        name = "payment_outcomes",
        indexes = {
                @Index(name = "idx_payment_outcomes_submission", columnList = "submission_id"),
                @Index(name = "idx_payment_outcomes_status", columnList = "status")
        }
)
@Getter
public class PaymentOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private PaymentSubmission submission;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentOutcomeStatus status;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal reportedAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amountInTripCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(precision = 18, scale = 8)
    private BigDecimal exchangeRate;

    @Column(name = "exchange_rate_scale")
    private Integer exchangeRateScale;

    @Column(name = "exchange_rate_requested_date")
    private LocalDate exchangeRateRequestedDate;

    @Column(name = "exchange_rate_effective_date")
    private LocalDate exchangeRateEffectiveDate;

    @Column(name = "exchange_rate_source", length = 64)
    private String exchangeRateSource;

    @Column(name = "exchange_rate_provider", length = 64)
    private String exchangeRateProvider;

    @Column(name = "exchange_rate_provider_timestamp", length = 128)
    private String exchangeRateProviderTimestamp;

    @Column(name = "calculation_version", length = 16)
    private String calculationVersion;

    public void applySnapshot(PaymentOutcomeSnapshot snapshot) {
        currency = snapshot.currency();
        exchangeRate = snapshot.exchangeRate();
        exchangeRateScale = snapshot.exchangeRateScale();
        exchangeRateRequestedDate = snapshot.requestedDate();
        exchangeRateEffectiveDate = snapshot.effectiveDate();
        exchangeRateSource = snapshot.source();
        exchangeRateProvider = snapshot.provider();
        exchangeRateProviderTimestamp = snapshot.providerTimestamp();
        calculationVersion = snapshot.calculationVersion();
    }

    @Column(length = 500)
    private String adminObservation;

    @Column(length = 255)
    private String resolvedByEmail;

    @Column(nullable = false)
    private LocalDateTime resolvedAt;

    @OneToMany(mappedBy = "outcome")
    private Set<PaymentAllocation> allocations = new LinkedHashSet<>();

    @PrePersist
    void onPersist() {
        if (resolvedAt == null) {
            resolvedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public PaymentSubmission getSubmission() {
        return submission;
    }

    public void setSubmission(PaymentSubmission submission) {
        this.submission = submission;
    }

    public PaymentOutcomeStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentOutcomeStatus status) {
        this.status = status;
    }

    public BigDecimal getReportedAmount() {
        return reportedAmount;
    }

    public void setReportedAmount(BigDecimal reportedAmount) {
        this.reportedAmount = reportedAmount;
    }

    public BigDecimal getAmountInTripCurrency() {
        return amountInTripCurrency;
    }

    public void setAmountInTripCurrency(BigDecimal amountInTripCurrency) {
        this.amountInTripCurrency = amountInTripCurrency;
    }

    public String getAdminObservation() {
        return adminObservation;
    }

    public void setAdminObservation(String adminObservation) {
        this.adminObservation = adminObservation;
    }

    public String getResolvedByEmail() {
        return resolvedByEmail;
    }

    public void setResolvedByEmail(String resolvedByEmail) {
        this.resolvedByEmail = resolvedByEmail;
    }

    public LocalDateTime getResolvedAt() {
        return resolvedAt;
    }

    public Set<PaymentAllocation> getAllocations() {
        return allocations;
    }

    public void setAllocations(Set<PaymentAllocation> allocations) {
        this.allocations = allocations;
    }
}
