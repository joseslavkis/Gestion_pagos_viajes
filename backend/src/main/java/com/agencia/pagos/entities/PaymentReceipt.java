package com.agencia.pagos.entities;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
    name = "payment_receipts",
    indexes = {
        @Index(name = "idx_payment_receipts_status", columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "installment_id", nullable = false)
    private Installment installment; // La cuota a la que pertenece este pago

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal reportedAmount; // El monto que el usuario dice haber pagado

    @Column(nullable = false)
    private LocalDate reportedPaymentDate; // Cuándo se realizó la transferencia/pago

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentMethod paymentMethod; // Metodo de pago informado por el usuario

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ReceiptStatus status = ReceiptStatus.PENDING;

    @Column(nullable = false)
    private String fileKey; // La ruta o clave en S3/almacenamiento para la imagen/PDF

    @Column(length = 500)
    private String adminObservation; // Explicación en caso de rechazo
}