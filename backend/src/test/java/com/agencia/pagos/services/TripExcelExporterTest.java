package com.agencia.pagos.services;

import com.agencia.pagos.dtos.internal.SpreadsheetReceiptRowDTO;
import com.agencia.pagos.dtos.response.SpreadsheetDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowInstallmentDTO;
import com.agencia.pagos.entities.InstallmentStatus;
import com.agencia.pagos.entities.InstallmentUiStatusCode;
import org.apache.poi.ss.usermodel.DataFormatter;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TripExcelExporterTest {

    private final TripExcelExporter exporter = new TripExcelExporter();
    private final DataFormatter dataFormatter = new DataFormatter();

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

        byte[] excelBytes = exporter.export(spreadsheet, "ARS", List.of());

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

            assertEquals("Abonado", sheet.getRow(1).getCell(10).getStringCellValue());
            assertEquals("Restante", sheet.getRow(1).getCell(11).getStringCellValue());

            assertEquals(1000.0, sheet.getRow(2).getCell(9).getNumericCellValue());
            assertEquals(0.0, sheet.getRow(2).getCell(10).getNumericCellValue());
            assertEquals(1000.0, sheet.getRow(2).getCell(11).getNumericCellValue());
            assertEquals("Al día", sheet.getRow(2).getCell(12).getStringCellValue());

            assertEquals(1000.0, sheet.getRow(2).getCell(14).getNumericCellValue());
            assertEquals(1000.0, sheet.getRow(2).getCell(15).getNumericCellValue());
            assertEquals(0.0, sheet.getRow(2).getCell(16).getNumericCellValue());
            assertEquals("Pagada", sheet.getRow(2).getCell(17).getStringCellValue());
            assertEquals("E2E8F0", hex(sheet.getRow(2).getCell(12).getCellStyle()));
            assertEquals("D4EDDA", hex(sheet.getRow(2).getCell(17).getCellStyle()));
        }
    }

    @Test
    void export_includesPaidAndRemainingColumnsForPartialInstallment() throws IOException {
        SpreadsheetDTO spreadsheet = new SpreadsheetDTO(
                "Mendoza",
                1,
                0,
                1L,
                List.of(new SpreadsheetRowDTO(
                        21L,
                        31L,
                        "Julia",
                        "Tutor",
                        "381555555",
                        "julia@test.com",
                        "Gomez",
                        "Sofi",
                        "40999888",
                        false,
                        List.of(
                                new SpreadsheetRowInstallmentDTO(
                                        3L,
                                        1,
                                        LocalDate.of(2026, 7, 10),
                                        new BigDecimal("1000.00"),
                                        BigDecimal.ZERO.setScale(2),
                                        BigDecimal.ZERO.setScale(2),
                                        new BigDecimal("1000.00"),
                                        new BigDecimal("500.00"),
                                        InstallmentStatus.YELLOW,
                                        InstallmentUiStatusCode.UNDER_REVIEW,
                                        "En revisión",
                                        "yellow"
                                )
                        )
                ))
        );

        byte[] excelBytes = exporter.export(spreadsheet, "ARS", List.of());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            var sheet = workbook.getSheetAt(0);

            assertEquals("Vencimiento", sheet.getRow(1).getCell(8).getStringCellValue());
            assertEquals("Total", sheet.getRow(1).getCell(9).getStringCellValue());
            assertEquals("Abonado", sheet.getRow(1).getCell(10).getStringCellValue());
            assertEquals("Restante", sheet.getRow(1).getCell(11).getStringCellValue());
            assertEquals("Estado", sheet.getRow(1).getCell(12).getStringCellValue());

            assertEquals(1000.0, sheet.getRow(2).getCell(9).getNumericCellValue());
            assertEquals(500.0, sheet.getRow(2).getCell(10).getNumericCellValue());
            assertEquals(500.0, sheet.getRow(2).getCell(11).getNumericCellValue());
            assertEquals("En revisión", sheet.getRow(2).getCell(12).getStringCellValue());
        }
    }

    @Test
    void verifyWorkbookHasTwoSheets() throws IOException {
        byte[] excelBytes = exporter.export(buildSummarySpreadsheet(), "ARS", List.of(buildReceiptRow()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            assertEquals(2, workbook.getNumberOfSheets());
        }
    }

    @Test
    void verifySummarySheetHeadersUnchanged() throws IOException {
        byte[] excelBytes = exporter.export(buildSummarySpreadsheet(), "ARS", List.of(buildReceiptRow()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            var sheet = workbook.getSheetAt(0);

            assertEquals("Apellido alumno", sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("Nombre alumno", sheet.getRow(1).getCell(1).getStringCellValue());
            assertEquals("DNI alumno", sheet.getRow(1).getCell(2).getStringCellValue());
            assertEquals("Completado", sheet.getRow(1).getCell(7).getStringCellValue());
            assertEquals("Vencimiento", sheet.getRow(1).getCell(8).getStringCellValue());
            assertEquals("Total", sheet.getRow(1).getCell(9).getStringCellValue());
            assertEquals("Abonado", sheet.getRow(1).getCell(10).getStringCellValue());
            assertEquals("Restante", sheet.getRow(1).getCell(11).getStringCellValue());
            assertEquals("Estado", sheet.getRow(1).getCell(12).getStringCellValue());
        }
    }

    @Test
    void verifyReceiptsSheetExists() throws IOException {
        byte[] excelBytes = exporter.export(buildSummarySpreadsheet(), "ARS", List.of(buildReceiptRow()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            assertNotNull(workbook.getSheet("Comprobantes"));
        }
    }

    @Test
    void verifyReceiptsSheetHeaders() throws IOException {
        byte[] excelBytes = exporter.export(buildSummarySpreadsheet(), "ARS", List.of(buildReceiptRow()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            var receiptsSheet = workbook.getSheet("Comprobantes");
            String[] expectedHeaders = {
                    "Cuota", "Vencimiento cuota", "Apellido alumno", "Nombre alumno", "DNI alumno", "Fecha",
                    "Medio", "Monto", "Moneda", "Cambio", "Monto convertido", "Estado", "Observación"
            };

            for (int i = 0; i < expectedHeaders.length; i++) {
                assertEquals(expectedHeaders[i], receiptsSheet.getRow(0).getCell(i).getStringCellValue());
            }
        }
    }

    @Test
    void verifyReceiptRowData() throws IOException {
        SpreadsheetReceiptRowDTO receipt = buildReceiptRow();
        byte[] excelBytes = exporter.export(buildSummarySpreadsheet(), "ARS", List.of(receipt));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            var row = workbook.getSheet("Comprobantes").getRow(1);

            assertEquals("2", dataFormatter.formatCellValue(row.getCell(0)));
            assertEquals("10/07/2026", dataFormatter.formatCellValue(row.getCell(1)));
            assertEquals("Pérez", dataFormatter.formatCellValue(row.getCell(2)));
            assertEquals("Lautaro", dataFormatter.formatCellValue(row.getCell(3)));
            assertEquals("40111222", dataFormatter.formatCellValue(row.getCell(4)));
            assertEquals("15/07/2026", dataFormatter.formatCellValue(row.getCell(5)));
            assertEquals("TRANSFER", dataFormatter.formatCellValue(row.getCell(6)));
            assertEquals(1000.50, row.getCell(7).getNumericCellValue());
            assertEquals("ARS", dataFormatter.formatCellValue(row.getCell(8)));
            assertEquals(1230.00, row.getCell(9).getNumericCellValue());
            assertEquals(999.99, row.getCell(10).getNumericCellValue());
            assertEquals("#,##0.00", row.getCell(7).getCellStyle().getDataFormatString());
            assertEquals("#,##0.00", row.getCell(9).getCellStyle().getDataFormatString());
            assertEquals("#,##0.00", row.getCell(10).getCellStyle().getDataFormatString());
            assertEquals("Aprobado", dataFormatter.formatCellValue(row.getCell(11)));
            assertEquals("Validado por admin", dataFormatter.formatCellValue(row.getCell(12)));
        }
    }

    @Test
    void verifyReceiptsOrderedByCuotaApellidoNombreFecha() throws IOException {
        SpreadsheetReceiptRowDTO first = new SpreadsheetReceiptRowDTO(
                1, LocalDate.of(2026, 6, 10), "Alvarez", "Ana", "30111222", LocalDate.of(2026, 6, 20),
                "TRANSFER", new BigDecimal("100.00"), "ARS", null, new BigDecimal("100.00"), "Aprobado", null
        );
        SpreadsheetReceiptRowDTO second = new SpreadsheetReceiptRowDTO(
                1, LocalDate.of(2026, 6, 10), "Alvarez", "Beto", "30111223", LocalDate.of(2026, 6, 15),
                "TRANSFER", new BigDecimal("100.00"), "ARS", null, new BigDecimal("100.00"), "Aprobado", null
        );
        SpreadsheetReceiptRowDTO third = new SpreadsheetReceiptRowDTO(
                2, LocalDate.of(2026, 7, 10), "Pérez", "Lautaro", "40111222", LocalDate.of(2026, 7, 15),
                "TRANSFER", new BigDecimal("100.00"), "ARS", null, new BigDecimal("100.00"), "Aprobado", null
        );

        byte[] excelBytes = exporter.export(buildSummarySpreadsheet(), "ARS", List.of(first, second, third));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            var sheet = workbook.getSheet("Comprobantes");
            assertEquals("1", dataFormatter.formatCellValue(sheet.getRow(1).getCell(0)));
            assertEquals("Alvarez", dataFormatter.formatCellValue(sheet.getRow(1).getCell(2)));
            assertEquals("Ana", dataFormatter.formatCellValue(sheet.getRow(1).getCell(3)));

            assertEquals("1", dataFormatter.formatCellValue(sheet.getRow(2).getCell(0)));
            assertEquals("Alvarez", dataFormatter.formatCellValue(sheet.getRow(2).getCell(2)));
            assertEquals("Beto", dataFormatter.formatCellValue(sheet.getRow(2).getCell(3)));

            assertEquals("2", dataFormatter.formatCellValue(sheet.getRow(3).getCell(0)));
            assertEquals("Pérez", dataFormatter.formatCellValue(sheet.getRow(3).getCell(2)));
        }
    }

    @Test
    void verifyReceiptsSheetWithNoReceipts() throws IOException {
        byte[] excelBytes = exporter.export(buildSummarySpreadsheet(), "ARS", List.of());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(excelBytes))) {
            var sheet = workbook.getSheet("Comprobantes");
            assertNotNull(sheet);
            assertNotNull(sheet.getRow(0));
            assertEquals(0, sheet.getLastRowNum());
        }
    }

    private SpreadsheetDTO buildSummarySpreadsheet() {
        return new SpreadsheetDTO(
                "Bariloche",
                1,
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
                        List.of(new SpreadsheetRowInstallmentDTO(
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
                        ))
                ))
        );
    }

    private SpreadsheetReceiptRowDTO buildReceiptRow() {
        return new SpreadsheetReceiptRowDTO(
                2,
                LocalDate.of(2026, 7, 10),
                "Pérez",
                "Lautaro",
                "40111222",
                LocalDate.of(2026, 7, 15),
                "TRANSFER",
                new BigDecimal("1000.50"),
                "ARS",
                new BigDecimal("1230.00"),
                new BigDecimal("999.99"),
                "Aprobado",
                "Validado por admin"
        );
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
