package com.agencia.pagos.entities;

import com.agencia.pagos.entities.user.User;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(
        name = "payment_submissions",
        indexes = {
                @Index(name = "idx_payment_submissions_status", columnList = "status"),
                @Index(name = "idx_payment_submissions_scope", columnList = "trip_id,user_id,student_id"),
                @Index(name = "idx_payment_submissions_user", columnList = "user_id"),
                @Index(name = "idx_payment_submissions_reported_date", columnList = "reported_payment_date")
        }
)
public class PaymentSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne
    @JoinColumn(name = "student_id")
    private Student student;

    @ManyToOne(optional = false)
    @JoinColumn(name = "anchor_installment_id", nullable = false)
    private Installment anchorInstallment;

    @ManyToOne
    @JoinColumn(name = "bank_account_id")
    private BankAccount bankAccount;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentSubmissionStatus status = PaymentSubmissionStatus.PENDING;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String fileKey;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "submission")
    private Set<PaymentOutcome> outcomes = new LinkedHashSet<>();

    @PrePersist
    void onPersist() {
        if (status == null) {
            status = PaymentSubmissionStatus.PENDING;
        }
        if (fileKey == null) {
            fileKey = "";
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Student getStudent() {
        return student;
    }

    public void setStudent(Student student) {
        this.student = student;
    }

    public Installment getAnchorInstallment() {
        return anchorInstallment;
    }

    public void setAnchorInstallment(Installment anchorInstallment) {
        this.anchorInstallment = anchorInstallment;
    }

    public BankAccount getBankAccount() {
        return bankAccount;
    }

    public void setBankAccount(BankAccount bankAccount) {
        this.bankAccount = bankAccount;
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

    public PaymentSubmissionStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentSubmissionStatus status) {
        this.status = status;
    }

    public String getFileKey() {
        return fileKey;
    }

    public void setFileKey(String fileKey) {
        this.fileKey = fileKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public Set<PaymentOutcome> getOutcomes() {
        return outcomes;
    }

    public void setOutcomes(Set<PaymentOutcome> outcomes) {
        this.outcomes = outcomes;
    }
}
