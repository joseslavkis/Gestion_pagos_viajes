package com.agencia.pagos.services;

import com.agencia.pagos.dtos.internal.SpreadsheetReceiptRowDTO;
import com.agencia.pagos.dtos.response.SpreadsheetDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowInstallmentDTO;
import com.agencia.pagos.entities.InstallmentUiStatusCode;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class TripExcelExporter {

    private static final int FIXED_COLUMNS = 8;
    private static final int INSTALLMENT_COLUMNS = 5;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public byte[] export(SpreadsheetDTO data, String currency, List<SpreadsheetReceiptRowDTO> receipts) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            String currencyCode = "USD".equalsIgnoreCase(currency) ? "USD" : "ARS";
            String sheetName = safeSheetName(data.tripName());
            var sheet = workbook.createSheet(sheetName);

            CellStyle groupHeaderStyle = createGroupHeaderStyle(workbook);
            CellStyle columnHeaderStyle = createColumnHeaderStyle(workbook);
            CellStyle rowEvenStyle = createDataRowStyle(workbook, "#ffffff");
            CellStyle rowOddStyle = createDataRowStyle(workbook, "#f0f7ff");
            CellStyle completedEvenStyle = createDataRowStyle(workbook, "#d4edda");
            CellStyle completedOddStyle = createDataRowStyle(workbook, "#d4edda");
            CellStyle amountEvenStyle = createAmountStyle(workbook, "#ffffff", currencyCode);
            CellStyle amountOddStyle = createAmountStyle(workbook, "#f0f7ff", currencyCode);
            Map<InstallmentUiStatusCode, CellStyle> statusStyles = createStatusStyles(workbook);

            var row0 = sheet.createRow(0);
            var row1 = sheet.createRow(1);

            String[] fixedHeaders = {
                    "Apellido alumno",
                    "Nombre alumno",
                    "DNI alumno",
                    "Apellido responsable",
                    "Nombre responsable",
                    "Email",
                    "Teléfono",
                    "Completado"
            };

            for (int col = 0; col < FIXED_COLUMNS; col++) {
                Cell groupCell = row0.createCell(col);
                groupCell.setCellStyle(groupHeaderStyle);
                groupCell.setCellValue("");

                Cell headerCell = row1.createCell(col);
                headerCell.setCellStyle(columnHeaderStyle);
                headerCell.setCellValue(fixedHeaders[col]);
            }

            int installmentsCount = data.installmentsCount() == null ? 0 : data.installmentsCount();
            int dynamicStart = FIXED_COLUMNS;
            for (int installmentNumber = 1; installmentNumber <= installmentsCount; installmentNumber++) {
                int startCol = dynamicStart + (installmentNumber - 1) * INSTALLMENT_COLUMNS;
                int endCol = startCol + INSTALLMENT_COLUMNS - 1;

                Cell groupCell = row0.createCell(startCol);
                groupCell.setCellStyle(groupHeaderStyle);
                groupCell.setCellValue("Cuota " + installmentNumber);

                for (int offset = 1; offset < INSTALLMENT_COLUMNS; offset++) {
                    Cell mergedCell = row0.createCell(startCol + offset);
                    mergedCell.setCellStyle(groupHeaderStyle);
                }

                sheet.addMergedRegion(new CellRangeAddress(0, 0, startCol, endCol));

                Cell dueHeader = row1.createCell(startCol);
                dueHeader.setCellStyle(columnHeaderStyle);
                dueHeader.setCellValue("Vencimiento");

                Cell totalHeader = row1.createCell(startCol + 1);
                totalHeader.setCellStyle(columnHeaderStyle);
                totalHeader.setCellValue("Total");

                Cell paidHeader = row1.createCell(startCol + 2);
                paidHeader.setCellStyle(columnHeaderStyle);
                paidHeader.setCellValue("Abonado");

                Cell remainingHeader = row1.createCell(startCol + 3);
                remainingHeader.setCellStyle(columnHeaderStyle);
                remainingHeader.setCellValue("Restante");

                Cell statusHeader = row1.createCell(startCol + 4);
                statusHeader.setCellStyle(columnHeaderStyle);
                statusHeader.setCellValue("Estado");
            }

            int rowIndex = 2;
            for (SpreadsheetRowDTO rowData : data.rows()) {
                boolean odd = ((rowIndex - 2) % 2) != 0;
                CellStyle baseStyle = odd ? rowOddStyle : rowEvenStyle;
                CellStyle completedStyle = odd ? completedOddStyle : completedEvenStyle;
                CellStyle amountStyle = odd ? amountOddStyle : amountEvenStyle;

                var row = sheet.createRow(rowIndex);

                writeTextCell(row, 0, rowData.studentLastname(), baseStyle);
                writeTextCell(row, 1, rowData.studentName(), baseStyle);
                writeTextCell(row, 2, rowData.studentDni(), baseStyle);
                writeTextCell(row, 3, rowData.lastname(), baseStyle);
                writeTextCell(row, 4, rowData.name(), baseStyle);
                writeTextCell(row, 5, rowData.email(), baseStyle);
                writeTextCell(row, 6, rowData.phone(), baseStyle);
                writeTextCell(row, 7, Boolean.TRUE.equals(rowData.userCompleted()) ? "Sí" : "No",
                        Boolean.TRUE.equals(rowData.userCompleted()) ? completedStyle : baseStyle);

                for (int installmentNumber = 1; installmentNumber <= installmentsCount; installmentNumber++) {
                    int baseCol = dynamicStart + (installmentNumber - 1) * INSTALLMENT_COLUMNS;
                    SpreadsheetRowInstallmentDTO installment = findInstallment(rowData, installmentNumber);

                    if (installment == null) {
                        writeTextCell(row, baseCol, "", baseStyle);
                        writeNumberCell(row, baseCol + 1, null, amountStyle);
                        writeNumberCell(row, baseCol + 2, null, amountStyle);
                        writeNumberCell(row, baseCol + 3, null, amountStyle);
                        writeTextCell(row, baseCol + 4, "", baseStyle);
                        continue;
                    }

                    String dueDate = installment.dueDate() == null ? "" : installment.dueDate().format(DATE_FORMATTER);
                    BigDecimal paidAmount = installment.paidAmount();
                    BigDecimal remainingAmount = calculateRemaining(installment.totalDue(), paidAmount);
                    writeTextCell(row, baseCol, dueDate, baseStyle);
                    writeNumberCell(row, baseCol + 1, installment.totalDue(), amountStyle);
                    writeNumberCell(row, baseCol + 2, paidAmount, amountStyle);
                    writeNumberCell(row, baseCol + 3, remainingAmount, amountStyle);
                    writeTextCell(row, baseCol + 4, installment.uiStatusLabel(),
                            statusStyles.getOrDefault(installment.uiStatusCode(), baseStyle));
                }

                rowIndex++;
            }

            int totalColumns = FIXED_COLUMNS + installmentsCount * INSTALLMENT_COLUMNS;
            for (int col = 0; col < totalColumns; col++) {
                if (col < FIXED_COLUMNS) {
                    sheet.autoSizeColumn(col);
                    int minWidth = 14 * 256;
                    if (sheet.getColumnWidth(col) < minWidth) {
                        sheet.setColumnWidth(col, minWidth);
                    }
                } else {
                    int installmentOffset = (col - FIXED_COLUMNS) % INSTALLMENT_COLUMNS;
                    int width = installmentOffset == 0 ? 14 : installmentOffset == 4 ? 16 : 12;
                    sheet.setColumnWidth(col, width * 256);
                }
            }

            sheet.createFreezePane(1, 2);

            createReceiptsSheet(workbook, receipts, currencyCode);

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not generate Excel spreadsheet", ex);
        }
    }

    private static SpreadsheetRowInstallmentDTO findInstallment(SpreadsheetRowDTO rowData, int installmentNumber) {
        if (rowData.installments() == null) {
            return null;
        }
        return rowData.installments().stream()
                .filter(i -> i.installmentNumber() != null && i.installmentNumber() == installmentNumber)
                .findFirst()
                .orElse(null);
    }

    private static BigDecimal calculateRemaining(BigDecimal totalDue, BigDecimal paidAmount) {
        if (totalDue == null) {
            return null;
        }
        return totalDue.subtract(paidAmount == null ? BigDecimal.ZERO : paidAmount);
    }

    private static void writeTextCell(org.apache.poi.ss.usermodel.Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellStyle(style);
        cell.setCellValue(value == null ? "" : value);
    }

    private static void writeNumberCell(org.apache.poi.ss.usermodel.Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellStyle(style);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
    }

    private static CellStyle createGroupHeaderStyle(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(new java.awt.Color(0x0d, 0x36, 0x57), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setWrapText(true);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private static CellStyle createColumnHeaderStyle(XSSFWorkbook workbook) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(new java.awt.Color(0x1a, 0x52, 0x76), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setWrapText(true);
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private static CellStyle createDataRowStyle(XSSFWorkbook workbook, String backgroundHex) {
        XSSFCellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(java.awt.Color.decode(backgroundHex), null));
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setWrapText(true);
        return style;
    }

    private static CellStyle createAmountStyle(XSSFWorkbook workbook, String backgroundHex, String currencyCode) {
        XSSFCellStyle style = (XSSFCellStyle) createDataRowStyle(workbook, backgroundHex);
        String format = "#,##0.00";
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        return style;
    }

    private static Map<InstallmentUiStatusCode, CellStyle> createStatusStyles(XSSFWorkbook workbook) {
        Map<InstallmentUiStatusCode, CellStyle> styles = new HashMap<>();
        styles.put(InstallmentUiStatusCode.PAID, createStatusStyle(workbook, "#d4edda", "#155724"));
        styles.put(InstallmentUiStatusCode.UP_TO_DATE, createStatusStyle(workbook, "#e2e8f0", "#334155"));
        styles.put(InstallmentUiStatusCode.UNDER_REVIEW, createStatusStyle(workbook, "#fff3cd", "#856404"));
        styles.put(InstallmentUiStatusCode.DUE_SOON, createStatusStyle(workbook, "#fff3cd", "#856404"));
        styles.put(InstallmentUiStatusCode.OVERDUE, createStatusStyle(workbook, "#f8d7da", "#721c24"));
        styles.put(InstallmentUiStatusCode.RECEIPT_REJECTED, createStatusStyle(workbook, "#f8d7da", "#721c24"));
        styles.put(InstallmentUiStatusCode.RETROACTIVE_DEBT, createStatusStyle(workbook, "#f8d7da", "#721c24"));
        return styles;
    }

    private static CellStyle createStatusStyle(XSSFWorkbook workbook, String backgroundHex, String textHex) {
        XSSFCellStyle style = (XSSFCellStyle) createDataRowStyle(workbook, backgroundHex);
        XSSFFont font = workbook.createFont();
        font.setColor(new XSSFColor(java.awt.Color.decode(textHex), null));
        font.setBold(true);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private static String safeSheetName(String tripName) {
        if (tripName == null || tripName.isBlank()) {
            return "Planilla";
        }
        String sanitized = tripName
                .replace("\\", "")
                .replace("/", "")
                .replace("*", "")
                .replace("[", "")
                .replace("]", "")
                .replace(":", "")
                .replace("?", "")
                .trim();
        if (sanitized.isBlank()) {
            return "Planilla";
        }
        return sanitized.substring(0, Math.min(31, sanitized.length()));
    }

    private void createReceiptsSheet(XSSFWorkbook workbook, List<SpreadsheetReceiptRowDTO> receipts, String currencyCode) {
        var sheet = workbook.createSheet("Comprobantes");
        CellStyle headerStyle = createColumnHeaderStyle(workbook);
        CellStyle rowEvenStyle = createDataRowStyle(workbook, "#ffffff");
        CellStyle rowOddStyle = createDataRowStyle(workbook, "#f0f7ff");
        CellStyle amountEvenStyle = createAmountStyle(workbook, "#ffffff", currencyCode);
        CellStyle amountOddStyle = createAmountStyle(workbook, "#f0f7ff", currencyCode);

        String[] headers = {
                "Cuota",
                "Vencimiento cuota",
                "Apellido alumno",
                "Nombre alumno",
                "DNI alumno",
                "Fecha",
                "Medio",
                "Monto",
                "Moneda",
                "Cambio",
                "Monto convertido",
                "Estado",
                "Observación"
        };

        var headerRow = sheet.createRow(0);
        for (int col = 0; col < headers.length; col++) {
            Cell cell = headerRow.createCell(col);
            cell.setCellStyle(headerStyle);
            cell.setCellValue(headers[col]);
        }

        int rowIndex = 1;
        for (SpreadsheetReceiptRowDTO receipt : receipts == null ? List.<SpreadsheetReceiptRowDTO>of() : receipts) {
            boolean odd = ((rowIndex - 1) % 2) != 0;
            CellStyle baseStyle = odd ? rowOddStyle : rowEvenStyle;
            CellStyle amountStyle = odd ? amountOddStyle : amountEvenStyle;
            var row = sheet.createRow(rowIndex++);

            if (receipt.installmentNumber() != null) {
                writeNumberCell(row, 0, BigDecimal.valueOf(receipt.installmentNumber()), baseStyle);
            } else {
                writeTextCell(row, 0, "", baseStyle);
            }
            writeTextCell(row, 1, receipt.installmentDueDate() == null ? "" : receipt.installmentDueDate().format(DATE_FORMATTER), baseStyle);
            writeTextCell(row, 2, receipt.studentLastname(), baseStyle);
            writeTextCell(row, 3, receipt.studentName(), baseStyle);
            writeTextCell(row, 4, receipt.studentDni(), baseStyle);
            writeTextCell(row, 5, receipt.reportedPaymentDate() == null ? "" : receipt.reportedPaymentDate().format(DATE_FORMATTER), baseStyle);
            writeTextCell(row, 6, receipt.paymentMethod(), baseStyle);
            writeNumberCell(row, 7, receipt.reportedAmount(), amountStyle);
            writeTextCell(row, 8, receipt.paymentCurrency(), baseStyle);
            writeNumberCell(row, 9, receipt.exchangeRate(), amountStyle);
            writeNumberCell(row, 10, receipt.amountInTripCurrency(), amountStyle);
            writeTextCell(row, 11, receipt.status(), baseStyle);
            writeTextCell(row, 12, receipt.adminObservation(), baseStyle);
        }

        int lastRow = Math.max(0, rowIndex - 1);
        sheet.setAutoFilter(new CellRangeAddress(0, lastRow, 0, 12));
        sheet.createFreezePane(0, 1);

        for (int col = 0; col <= 12; col++) {
            sheet.autoSizeColumn(col);
            int minWidth = switch (col) {
                case 11 -> 14 * 256;
                case 12 -> 24 * 256;
                default -> 12 * 256;
            };
            if (sheet.getColumnWidth(col) < minWidth) {
                sheet.setColumnWidth(col, minWidth);
            }
        }
    }
}
