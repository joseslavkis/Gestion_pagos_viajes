package com.agencia.pagos.services;

import com.agencia.pagos.dtos.response.SpreadsheetDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowInstallmentDTO;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.InstallmentUiStatusCode;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TripExcelExporterTest {

    private final TripExcelExporter exporter = new TripExcelExporter();

    @Test
    void export_placesStudentDataInFirstColumnsAndUsesNeutralColorForUpToDate() throws IOException {
        SpreadsheetDTO spreadsheet = new SpreadsheetDTO(
                "Bariloche",
                2,
                0,
                1L,
                List.of(new SpreadsheetRowDTO(
                        20L,
                        30L,
                        "Ana",
                        "Parent",
                        "381123123",
                        "ana@test.com",
                        "Perez",
                        "Luca",
                        "40111222",
                        false,
                        List.of(
                                new SpreadsheetRowInstallmentDTO(
                                        1L,
                                        1,
                                        LocalDate.of(2026, 5, 10),
                                        new BigDecimal("1000.00"),
                                        BigDecimal.ZERO.setScale(2),
                                        BigDecimal.ZERO.setScale(2),
                                        new BigDecimal("1000.00"),
                                        BigDecimal.ZERO.setScale(2),
                                        InstallmentStatus.YELLOW,
                                        InstallmentUiStatusCode.UP_TO_DATE,
                                        "Al día",
                                        "green"
                                ),
                                new SpreadsheetRowInstallmentDTO(
                                        2L,
                                        2,
                                        LocalDate.of(2026, 6, 10),
                                        new BigDecimal("1000.00"),
                                        BigDecimal.ZERO.setScale(2),
                                        BigDecimal.ZERO.setScale(2),
                                        new BigDecimal("1000.00"),
                                        new BigDecimal("1000.00"),
                                        InstallmentStatus.GREEN,
                                        InstallmentUiStatusCode.PAID,
                                        "Pagada",
                                        "green"
                                )
                        )
                ))
        );

        byte[] excelBytes = exporter.export(spreadsheet, "ARS");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            var sheet = workbook.getSheetAt(0);

            assertEquals("Apellido alumno", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Nombre alumno", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("DNI alumno", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("Completado", sheet.getRow(1).getCell(7).getStringCellValue());

            assertEquals("Perez", sheet.getRow(2).getCell(0).getStringCellValue());
            assertEquals("Luca", sheet.getRow(2).getCell(1).getStringCellValue());
            assertEquals("40111222", sheet.getRow(2).getCell(2).getStringCellValue());
            assertEquals("Parent", sheet.getRow(2).getCell(3).getStringCellValue());
            assertEquals("Ana", sheet.getRow(2).getCell(4).getStringCellValue());
            assertEquals("ana@test.com", sheet.getRow(2).getCell(5).getStringCellValue());

            assertEquals("Al día", sheet.getRow(2).getCell(10).getStringCellValue());
            assertEquals("Pagada", sheet.getRow(2).getCell(13).getStringCellValue());
            assertEquals("E2E8F0", hex(sheet.getRow(2).getCell(10).getCellStyle()));
            assertEquals("D4EDDA", hex(sheet.getRow(2).getCell(13).getCellStyle()));
        }
    }

    private String hex(org.apache.poi.ss.usermodel.CellStyle style) {
        XSSFColor color = ((XSSFCellStyle) style).getFillForegroundColorColor();
        byte[] rgb = color == null ? null : color.getRGB();
        if (rgb == null || ((XSSFCellStyle) style).getFillPattern() == FillPatternType.NO_FILL) {
            return "";
        }

        return String.format("%02X%02X%02X", rgb[0] & 0xFF, rgb[1] & 0xFF, rgb[2] & 0xFF);
    }
}
