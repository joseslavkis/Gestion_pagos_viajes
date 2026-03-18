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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
    name = "installments",
    indexes = {
        @Index(name = "idx_installments_trip_user", columnList = "trip_id,user_id"),
        @Index(name = "idx_installments_status", columnList = "status"),
        @Index(name = "idx_installments_due_date", columnList = "due_date")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
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

    @Column(nullable = false)
    private Integer installmentNumber; // Número secuencial (ej: Cuota 1, 2, 3) para ordenamiento en la grilla

    @Column(nullable = false)
    private LocalDate dueDate; // Fecha límite de pago; se usa para calcular si la cuota pasa a estado ROJO

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal capitalAmount; // Monto base de la cuota según el plan de pagos original

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal retroactiveAmount = BigDecimal.ZERO; // Monto de cuotas anteriores si el usuario se sumó tarde al viaje

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal fineAmount = BigDecimal.ZERO; // Monto fijo de multa aplicado automáticamente si hay más de una cuota vencida

    @Column(nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal totalDue = BigDecimal.ZERO; // Total exigible recalculado automáticamente para evitar inconsistencias

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private InstallmentStatus status = InstallmentStatus.YELLOW; // Estado del semáforo: GREEN (pago), YELLOW (pendiente), RED (vencido)

    @PrePersist
    @PreUpdate
    private void recalculateTotalDue() {
        BigDecimal capital = capitalAmount == null ? BigDecimal.ZERO : capitalAmount;
        BigDecimal retroactive = retroactiveAmount == null ? BigDecimal.ZERO : retroactiveAmount;
        BigDecimal fine = fineAmount == null ? BigDecimal.ZERO : fineAmount;
        totalDue = capital.add(retroactive).add(fine);
    }

}