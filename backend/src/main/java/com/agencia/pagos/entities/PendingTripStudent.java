package com.agencia.pagos.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "pending_trip_students",
    uniqueConstraints = @UniqueConstraint(name = "uk_pending_trip_students_trip_dni", columnNames = {"trip_id", "student_dni"}),
    indexes = {
        @Index(name = "idx_pending_trip_students_trip_id", columnList = "trip_id"),
        @Index(name = "idx_pending_trip_students_student_dni", columnList = "student_dni")
    }
)
public class PendingTripStudent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "student_dni", nullable = false, length = 8)
    private String studentDni;

    public Long getId() {
        return id;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public String getStudentDni() {
        return studentDni;
    }

    public void setStudentDni(String studentDni) {
        this.studentDni = studentDni;
    }
}
