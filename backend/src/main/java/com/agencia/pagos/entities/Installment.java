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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
    name = "installments",
    indexes = {
        @Index(name = "idx_installments_trip_user", columnList = "trip_id,user_id"),
        @Index(name = "idx_installments_trip_user_student", columnList = "trip_id,user_id,student_id"),
        @Index(name = "idx_installments_status", columnList = "status"),
        @Index(name = "idx_installments_due_date", columnList = "due_date")
    }
)

public class Installment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; // Identificador único autoincremental (Primary Key)

    @ManyToOne(optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip; // Vinculación con el viaje específico para acceder a su configuración global

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user; // El padre o tutor responsable de pagar esta cuota en particular

    @ManyToOne
    @JoinColumn(name = "student_id")
    private Student student;

    @Column(nullable = false)
    private Integer installmentNumber; // Número secuencial (ej: Cuota 1, 2, 3) para ordenamiento en la grilla

    @Column(nullable = false)
    private LocalDate dueDate; // Fecha límite de pago; se usa para calcular si la cuota pasa a estado ROJO

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal capitalAmount; // Monto base de la cuota según el plan de pagos original

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal retroactiveAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal fineAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalDue = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InstallmentStatus status = InstallmentStatus.YELLOW;

    @PrePersist
    @PreUpdate
    private void onPersist() {
        recalculateTotalDue();
    }

    public void recalculateTotalDue() {
        BigDecimal capital = capitalAmount == null ? BigDecimal.ZERO : capitalAmount;
        BigDecimal fine = fineAmount == null ? BigDecimal.ZERO : fineAmount;
        // retroactiveAmount NO se suma al totalDue — es solo informativo.
        // Cada cuota RETROACTIVE tiene su propio totalDue = su capitalAmount.
        // La cascada de pagos las cubre individualmente en orden.
        totalDue = capital.add(fine);
    }

    public Installment() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Trip getTrip() { return trip; }
    public void setTrip(Trip trip) { this.trip = trip; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }

    public Integer getInstallmentNumber() { return installmentNumber; }
    public void setInstallmentNumber(Integer installmentNumber) { this.installmentNumber = installmentNumber; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public BigDecimal getCapitalAmount() { return capitalAmount; }
    public void setCapitalAmount(BigDecimal capitalAmount) { this.capitalAmount = capitalAmount; }

    public BigDecimal getRetroactiveAmount() { return retroactiveAmount; }
    public void setRetroactiveAmount(BigDecimal retroactiveAmount) { this.retroactiveAmount = retroactiveAmount; }

    public BigDecimal getFineAmount() { return fineAmount; }
    public void setFineAmount(BigDecimal fineAmount) { this.fineAmount = fineAmount; }

    public BigDecimal getTotalDue() { return totalDue; }
    public void setTotalDue(BigDecimal totalDue) { this.totalDue = totalDue; }

    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }

    public InstallmentStatus getStatus() { return status; }
    public void setStatus(InstallmentStatus status) { this.status = status; }

}
