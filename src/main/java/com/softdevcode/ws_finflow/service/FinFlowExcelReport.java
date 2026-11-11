package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.entity.Transaction;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.*;
import org.apache.poi.poifs.crypt.HashAlgorithm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FinFlow branded Excel export. Unlike CSV, .xlsx preserves column boundaries,
 * dates, numeric values, styles, filters and formulas regardless of Excel locale.
 * Data remains restricted to the caller's already-filtered transactions.
 */
public final class FinFlowExcelReport {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final XSSFColor NAVY = rgb(15, 23, 42);
    private static final XSSFColor BLUE = rgb(91, 110, 245);
    private static final XSSFColor MUTED = rgb(100, 116, 139);
    private static final XSSFColor WHITE = rgb(255, 255, 255);
    private static final XSSFColor PALE = rgb(242, 246, 253);
    private static final XSSFColor GREEN = rgb(22, 163, 74);
    private static final XSSFColor RED = rgb(220, 38, 38);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private FinFlowExcelReport() {}

    public static byte[] build(List<Transaction> transactions, Map<Long, String> categories, String subtitle) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet cover = book.createSheet("Resumen");
            XSSFSheet detail = book.createSheet("Movimientos");

            XSSFCellStyle brand = colored(book, NAVY, WHITE, 20, true);
            XSSFCellStyle sub = colored(book, NAVY, WHITE, 11, false);
            XSSFCellStyle heading = colored(book, BLUE, WHITE, 10, true);
            XSSFCellStyle kpi = colored(book, PALE, NAVY, 17, true);
            XSSFCellStyle note = colored(book, PALE, MUTED, 10, false);
            XSSFCellStyle tableHead = colored(book, NAVY, WHITE, 10, true);
            XSSFCellStyle text = colored(book, WHITE, NAVY, 10, false);
            XSSFCellStyle textAlt = colored(book, PALE, NAVY, 10, false);
            XSSFCellStyle income = colored(book, WHITE, GREEN, 10, false);
            XSSFCellStyle incomeAlt = colored(book, PALE, GREEN, 10, false);
            XSSFCellStyle expense = colored(book, WHITE, RED, 10, false);
            XSSFCellStyle expenseAlt = colored(book, PALE, RED, 10, false);
            XSSFCellStyle date = colored(book, WHITE, NAVY, 10, false);
            XSSFCellStyle dateAlt = colored(book, PALE, NAVY, 10, false);
            XSSFCellStyle number = colored(book, WHITE, NAVY, 10, false);
            XSSFCellStyle numberAlt = colored(book, PALE, NAVY, 10, false);
            XSSFCellStyle kpiNumber = colored(book, PALE, NAVY, 17, true);
            short dateFormat = book.createDataFormat().getFormat("dd/mm/yyyy");
            short amountFormat = book.createDataFormat().getFormat("#,##0.00");
            date.setDataFormat(dateFormat);
            dateAlt.setDataFormat(dateFormat);
            number.setDataFormat(amountFormat);
            numberAlt.setDataFormat(amountFormat);
            income.setDataFormat(amountFormat);
            incomeAlt.setDataFormat(amountFormat);
            expense.setDataFormat(amountFormat);
            expenseAlt.setDataFormat(amountFormat);
            kpiNumber.setDataFormat(amountFormat);

            for (Sheet sheet : new Sheet[]{cover, detail}) {
                int[] widths = {16, 42, 16, 27, 19, 19, 15, 19};
                for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
                sheet.setDisplayGridlines(false);
                sheet.getPrintSetup().setLandscape(true);
                sheet.setFitToPage(true);
            }

            merged(cover, 0, 0, 7, "FinFlow  /  REPORTE FINANCIERO", brand);
            cover.getRow(0).setHeightInPoints(47);
            merged(cover, 1, 0, 7, safe(subtitle), sub);
            merged(cover, 2, 0, 7, "Generado el " +
                    LocalDate.now(ZoneId.of("America/Bogota")).format(DISPLAY_DATE) + "  •  COP  •  SOLO LECTURA", note);
            cover.getRow(2).setHeightInPoints(26);
            merged(cover, 4, 0, 1, "INGRESOS", heading);
            merged(cover, 4, 2, 3, "GASTOS", heading);
            merged(cover, 4, 4, 5, "BALANCE", heading);
            merged(cover, 4, 6, 7, "MOVIMIENTOS", heading);

            int firstExcelRow = 5;
            int lastExcelRow = Math.max(firstExcelRow, transactions.size() + 4);
            Cell inc = merged(cover, 5, 0, 1, "", kpiNumber);
            inc.setCellFormula("SUMIFS(Movimientos!E" + firstExcelRow + ":E" + lastExcelRow + ",Movimientos!G" + firstExcelRow + ":G" + lastExcelRow + ",\"COP\",Movimientos!H" + firstExcelRow + ":H" + lastExcelRow + ",\"Completado\")");
            Cell exp = merged(cover, 5, 2, 3, "", kpiNumber);
            exp.setCellFormula("SUMIFS(Movimientos!F" + firstExcelRow + ":F" + lastExcelRow + ",Movimientos!G" + firstExcelRow + ":G" + lastExcelRow + ",\"COP\",Movimientos!H" + firstExcelRow + ":H" + lastExcelRow + ",\"Completado\")");
            Cell balance = merged(cover, 5, 4, 5, "", kpiNumber);
            balance.setCellFormula("A6-C6");
            merged(cover, 5, 6, 7, Integer.toString(transactions.size()), kpi);
            cover.getRow(5).setHeightInPoints(42);
            merged(cover, 8, 0, 7, "Indicadores sobre operaciones completadas en COP, no sobre saldos bancarios.", note);
            merged(cover, 10, 0, 7, "Abre la pestaña Movimientos para filtrar, ordenar o analizar cada transacción.", note);
            merged(cover, 12, 0, 7, "FinFlow  •  Toma el control de tu dinero y construye tu futuro.", note);

            merged(detail, 0, 0, 7, "FinFlow  /  MOVIMIENTOS", brand);
            detail.getRow(0).setHeightInPoints(40);
            merged(detail, 1, 0, 7, safe(subtitle), sub);
            Row header = detail.createRow(3);
            header.setHeightInPoints(29);
            String[] titles = {"Fecha", "Descripción", "Tipo", "Categoría",
                    "Ingreso", "Gasto", "Moneda", "Estado"};
            for (int c = 0; c < titles.length; c++) {
                putString(header, c, titles[c], tableHead);
            }

            for (int i = 0; i < transactions.size(); i++) {
                Transaction tx = transactions.get(i);
                Row row = detail.createRow(i + 4);
                row.setHeightInPoints(23);
                boolean alt = (i & 1) == 1;
                XSSFCellStyle base = alt ? textAlt : text;
                if (tx.getDate() != null) {
                    Cell cell = row.createCell(0);
                    cell.setCellValue(tx.getDate().toLocalDate());
                    cell.setCellStyle(alt ? dateAlt : date);
                } else putString(row, 0, "", base);
                putString(row, 1, tx.getDescription(), base);
                putString(row, 2, typeLabel(tx.getType()), base);
                putString(row, 3, categoryName(tx, categories), base);
                double amount = tx.getAmount() != null && Double.isFinite(tx.getAmount())
                        ? Math.abs(tx.getAmount()) : 0d;
                if ("income".equals(tx.getType())) {
                    putNumber(row, 4, amount, alt ? incomeAlt : income);
                    putString(row, 5, "", base);
                } else if ("expense".equals(tx.getType())) {
                    putString(row, 4, "", base);
                    putNumber(row, 5, amount, alt ? expenseAlt : expense);
                } else {
                    putString(row, 4, "", base);
                    putString(row, 5, "", base);
                }
                putString(row, 6, tx.getCurrency(), base);
                putString(row, 7, statusLabel(tx.getStatus()), base);
            }

            detail.createFreezePane(0, 4);
            detail.setAutoFilter(new CellRangeAddress(3, Math.max(3, transactions.size() + 3), 0, 7));
            detail.setRepeatingRows(new CellRangeAddress(3, 3, 0, 7));
            book.getCreationHelper().createFormulaEvaluator().evaluateAll();
            book.setForceFormulaRecalculation(true);
            // Excel's native protection prevents accidental edits in compliant apps.
            // A new, undisclosed password per export avoids a shared hardcoded key.
            // Worksheet protection is NOT cryptographic immutability: a copy can still be modified.
            byte[] secretBytes = new byte[24];
            SECURE_RANDOM.nextBytes(secretBytes);
            String password = HexFormat.of().formatHex(secretBytes);
            cover.protectSheet(password);
            cover.setSheetPassword(password, HashAlgorithm.sha512);
            detail.protectSheet(password);
            detail.setSheetPassword(password, HashAlgorithm.sha512);
            // Reading and filter navigation remain possible; cell edits are blocked in Excel.
            detail.lockAutoFilter(false);
            book.lockStructure();
            book.setWorkbookPassword(password, HashAlgorithm.sha512);
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el Excel FinFlow", e);
        }
    }

    static String categoryName(Transaction tx, Map<Long, String> categories) {
        if (tx.getCategoryId() == null) return "Sin categoría";
        return categories.getOrDefault(tx.getCategoryId(), "Categoría eliminada");
    }

    private static String typeLabel(String type) {
        if ("income".equals(type)) return "Ingreso";
        if ("expense".equals(type)) return "Gasto";
        return type == null ? "" : type;
    }

    private static String statusLabel(String status) {
        if ("completed".equals(status)) return "Completado";
        if ("pending".equals(status)) return "Pendiente";
        if ("cancelled".equals(status)) return "Cancelado";
        return status == null ? "" : status;
    }

    private static String safe(String value) {
        if (value == null) return "";
        return value.length() > 32760 ? value.substring(0, 32760) : value;
    }

    private static void putString(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col, CellType.STRING);
        cell.setCellValue(safe(value)); // POI writes STRING, never a user-provided formula.
        cell.setCellStyle(style);
    }

    private static void putNumber(Row row, int col, double value, CellStyle style) {
        Cell cell = row.createCell(col, CellType.NUMERIC);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static Cell merged(Sheet sheet, int rowIdx, int first, int last,
                               String value, CellStyle style) {
        Row row = sheet.getRow(rowIdx);
        if (row == null) row = sheet.createRow(rowIdx);
        Cell origin = null;
        for (int c = first; c <= last; c++) {
            Cell cell = row.createCell(c);
            cell.setCellStyle(style);
            if (c == first) origin = cell;
        }
        if (last > first) sheet.addMergedRegion(new CellRangeAddress(rowIdx, rowIdx, first, last));
        origin.setCellValue(safe(value));
        return origin;
    }

    private static XSSFCellStyle colored(XSSFWorkbook book, XSSFColor background,
                                         XSSFColor foreground, int size, boolean bold) {
        XSSFCellStyle style = book.createCellStyle();
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setFillForegroundColor(background);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setWrapText(false);
        XSSFFont font = book.createFont();
        font.setFontName("Aptos");
        font.setFontHeightInPoints((short) size);
        font.setBold(bold);
        font.setColor(foreground);
        style.setFont(font);
        return style;
    }

    private static XSSFColor rgb(int r, int g, int b) {
        return new XSSFColor(new byte[]{(byte) r, (byte) g, (byte) b}, new DefaultIndexedColorMap());
    }
}
