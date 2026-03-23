package com.agencia.pagos.services;

import com.agencia.pagos.dtos.response.SpreadsheetDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowDTO;
import com.agencia.pagos.dtos.response.SpreadsheetRowInstallmentDTO;
import com.agencia.pagos.entities.InstallmentStatus;
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
import java.util.Map;

@Component
public class TripExcelExporter {

    private static final int FIXED_COLUMNS = 8;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    public byte[] export(SpreadsheetDTO data, String currency) {
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

            Map<InstallmentStatus, CellStyle> evenStatusStyles = createStatusStyles(workbook, true);
            Map<InstallmentStatus, CellStyle> oddStatusStyles = createStatusStyles(workbook, false);

            var row0 = sheet.createRow(0);
            var row1 = sheet.createRow(1);

            String[] fixedHeaders = {
                    "Apellido",
                    "Nombre",
                    "Email",
                    "Teléfono",
                    "Alumno",
                    "Colegio",
                    "Curso",
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
                int startCol = dynamicStart + (installmentNumber - 1) * 3;
                int endCol = startCol + 2;

                Cell groupCell = row0.createCell(startCol);
                groupCell.setCellStyle(groupHeaderStyle);
                groupCell.setCellValue("Cuota " + installmentNumber);

                Cell mergedMiddleCell = row0.createCell(startCol + 1);
                mergedMiddleCell.setCellStyle(groupHeaderStyle);
                Cell mergedLastCell = row0.createCell(startCol + 2);
                mergedLastCell.setCellStyle(groupHeaderStyle);

                sheet.addMergedRegion(new CellRangeAddress(0, 0, startCol, endCol));

                Cell dueHeader = row1.createCell(startCol);
                dueHeader.setCellStyle(columnHeaderStyle);
                dueHeader.setCellValue("Vencimiento");

                Cell totalHeader = row1.createCell(startCol + 1);
                totalHeader.setCellStyle(columnHeaderStyle);
                totalHeader.setCellValue("Total");

                Cell statusHeader = row1.createCell(startCol + 2);
                statusHeader.setCellStyle(columnHeaderStyle);
                statusHeader.setCellValue("Estado");
            }

            int rowIndex = 2;
            for (SpreadsheetRowDTO rowData : data.rows()) {
                boolean odd = ((rowIndex - 2) % 2) != 0;
                CellStyle baseStyle = odd ? rowOddStyle : rowEvenStyle;
                CellStyle completedStyle = odd ? completedOddStyle : completedEvenStyle;
                CellStyle amountStyle = odd ? amountOddStyle : amountEvenStyle;
                Map<InstallmentStatus, CellStyle> statusStyles = odd ? oddStatusStyles : evenStatusStyles;

                var row = sheet.createRow(rowIndex);

                writeTextCell(row, 0, rowData.lastname(), baseStyle);
                writeTextCell(row, 1, rowData.name(), baseStyle);
                writeTextCell(row, 2, rowData.email(), baseStyle);
                writeTextCell(row, 3, rowData.phone(), baseStyle);
                writeTextCell(row, 4, rowData.studentName(), baseStyle);
                writeTextCell(row, 5, rowData.schoolName(), baseStyle);
                writeTextCell(row, 6, rowData.courseName(), baseStyle);
                writeTextCell(row, 7, Boolean.TRUE.equals(rowData.userCompleted()) ? "Sí" : "No",
                        Boolean.TRUE.equals(rowData.userCompleted()) ? completedStyle : baseStyle);

                for (int installmentNumber = 1; installmentNumber <= installmentsCount; installmentNumber++) {
                    int baseCol = dynamicStart + (installmentNumber - 1) * 3;
                    SpreadsheetRowInstallmentDTO installment = findInstallment(rowData, installmentNumber);

                    if (installment == null) {
                        writeTextCell(row, baseCol, "", baseStyle);
                        writeNumberCell(row, baseCol + 1, null, amountStyle);
                        writeTextCell(row, baseCol + 2, "", baseStyle);
                        continue;
                    }

                    String dueDate = installment.dueDate() == null ? "" : installment.dueDate().format(DATE_FORMATTER);
                    writeTextCell(row, baseCol, dueDate, baseStyle);
                    writeNumberCell(row, baseCol + 1, installment.totalDue(), amountStyle);
                    writeTextCell(row, baseCol + 2, toStatusLabel(installment.status()),
                            statusStyles.getOrDefault(installment.status(), baseStyle));
                }

                rowIndex++;
            }

            int totalColumns = FIXED_COLUMNS + installmentsCount * 3;
            for (int col = 0; col < totalColumns; col++) {
                if (col < FIXED_COLUMNS) {
                    sheet.autoSizeColumn(col);
                    int minWidth = 14 * 256;
                    if (sheet.getColumnWidth(col) < minWidth) {
                        sheet.setColumnWidth(col, minWidth);
                    }
                } else {
                    sheet.setColumnWidth(col, 12 * 256);
                }
            }

            sheet.createFreezePane(1, 2);

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
        String format = "USD".equalsIgnoreCase(currencyCode) ? "#,##0.00" : "#,##0.00";
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        return style;
    }

    private static Map<InstallmentStatus, CellStyle> createStatusStyles(XSSFWorkbook workbook, boolean evenRow) {
        Map<InstallmentStatus, CellStyle> styles = new HashMap<>();
        String fallback = evenRow ? "#ffffff" : "#f0f7ff";
        styles.put(InstallmentStatus.GREEN, createStatusStyle(workbook, "#d4edda", "#155724"));
        styles.put(InstallmentStatus.YELLOW, createStatusStyle(workbook, "#fff3cd", "#856404"));
        styles.put(InstallmentStatus.RED, createStatusStyle(workbook, "#f8d7da", "#721c24"));
        styles.put(InstallmentStatus.RETROACTIVE, createStatusStyle(workbook, "#e2e8f0", "#334155"));
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

    private static String toStatusLabel(InstallmentStatus status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case GREEN -> "Pagada";
            case YELLOW -> "Pendiente";
            case RED -> "Vencida";
            case RETROACTIVE -> "Retroactiva";
        };
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
}
