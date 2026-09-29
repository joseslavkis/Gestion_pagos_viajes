package com.agencia.pagos.payment;

import jakarta.persistence.*;

@Entity
@Table(name = "payment_submission_attachments", uniqueConstraints =
        @UniqueConstraint(name = "uq_payment_submission_attachment_position", columnNames = {"submission_id", "position"}))
public class PaymentSubmissionAttachment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "submission_id", nullable = false)
    private PaymentSubmission submission;

    @Column(nullable = false)
    private Integer position;

    @Column(name = "file_key", nullable = false, columnDefinition = "TEXT")
    private String fileKey;

    protected PaymentSubmissionAttachment() {}

    public PaymentSubmissionAttachment(PaymentSubmission submission, int position, String fileKey) {
        this.submission = submission;
        this.position = position;
        this.fileKey = fileKey;
    }

    public String getFileKey() { return fileKey; }
    public Integer getPosition() { return position; }
    public void setFileKey(String fileKey) { this.fileKey = fileKey; }
}
