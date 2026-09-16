package com.agencia.pagos.trip.dto;

public record TripStudentAdminDTO(
        String studentDni,
        Long studentId,
        String studentName,
        Long parentUserId,
        String parentFullName,
        String parentEmail,
        String status,
        Integer installmentsCount
) {}
