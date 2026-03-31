package com.agencia.pagos.dtos.response;

public record TripStudentAdminDTO(
        String studentDni,
        Long studentId,
        String studentName,
        String schoolName,
        String courseName,
        Long parentUserId,
        String parentFullName,
        String parentEmail,
        String status,
        Integer installmentsCount
) {}
