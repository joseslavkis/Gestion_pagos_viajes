package com.agencia.pagos.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(
        name = "payment_allocations",
        indexes = {
                @Index(name = "idx_payment_allocations_outcome", columnList = "outcome_id"),
                @Index(name = "idx_payment_allocations_installment", columnList = "installment_id")
        }
)
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "outcome_id", nullable = false)
    private PaymentOutcome outcome;

    @ManyToOne(optional = false)
    @JoinColumn(name = "installment_id", nullable = false)
    private Installment installment;

    @Column(nullable = false)
    private Integer allocationOrder;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal reportedAmount;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amountInTripCurrency;

    public Long getId() {
        return id;
    }

    public PaymentOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(PaymentOutcome outcome) {
        this.outcome = outcome;
    }

    public Installment getInstallment() {
        return installment;
    }

    public void setInstallment(Installment installment) {
        this.installment = installment;
    }

    public Integer getAllocationOrder() {
        return allocationOrder;
    }

    public void setAllocationOrder(Integer allocationOrder) {
        this.allocationOrder = allocationOrder;
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
}
