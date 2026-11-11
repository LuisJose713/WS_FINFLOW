package com.softdevcode.ws_finflow.service;

import com.itextpdf.kernel.pdf.EncryptionConstants;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.softdevcode.ws_finflow.dto.TransactionFilter;
import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.entity.Transaction;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FinFlowReportExportTest {
    @Mock EntityManager em;
    @Mock TypedQuery<Transaction> query;
    @Mock CategoryRepository categories;
    @InjectMocks TransactionService service;

    private Transaction tx;

    @BeforeEach
    void setUp() {
        Category c = new Category();
        c.setId(3L);
        c.setName("Transporte");
        c.setUserId(42);
        when(categories.findVisibleTo(42)).thenReturn(List.of(c));
        tx = new Transaction();
        tx.setId(19L);
        tx.setUserId(42);
        tx.setType("expense");
        tx.setAmount(125000.50);
        tx.setCategoryId(3L);
        tx.setCurrency("COP");
        tx.setDate(LocalDateTime.of(2026, 11, 11, 10, 0));
        tx.setDescription("Taxi");
        tx.setStatus("completed");
        when(em.createQuery(anyString(), eq(Transaction.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(tx));
    }

    @Test
    void pdfShowsRealCategoryAndFinflowBrand() throws Exception {
        byte[] bytes = service.exportPdf(42, new TransactionFilter());
        assertTrue(bytes.length > 250);
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
            String text = PdfTextExtractor.getTextFromPage(pdf.getPage(1));
            assertTrue(text.contains("FinFlow"));
            assertTrue(text.contains("Transporte"));
            assertFalse(text.contains("#3"));
            // User can open and read, but receives no owner/edit privileges.
            assertTrue(pdf.getReader().isEncrypted());
            assertFalse(pdf.getReader().isOpenedWithFullPermission());
            long permissions = pdf.getReader().getPermissions();
            assertEquals(0L, permissions & EncryptionConstants.ALLOW_MODIFY_CONTENTS);
            assertEquals(0L, permissions & EncryptionConstants.ALLOW_MODIFY_ANNOTATIONS);
            assertEquals(0L, permissions & EncryptionConstants.ALLOW_FILL_IN);
            assertEquals(0L, permissions & EncryptionConstants.ALLOW_ASSEMBLY);
        }
        verify(categories).findVisibleTo(42);
    }

    @Test
    void excelHasRealColumnsNumericAmountsFormatsAndSummary() throws Exception {
        byte[] bytes = service.exportXlsx(42, new TransactionFilter());
        assertEquals('P', bytes[0]);
        assertEquals('K', bytes[1]);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(2, wb.getNumberOfSheets());
            assertEquals("Resumen", wb.getSheetAt(0).getSheetName());
            var sheet = wb.getSheet("Movimientos");
            assertEquals("Categoría", sheet.getRow(3).getCell(3).getStringCellValue());
            var row = sheet.getRow(4);
            assertEquals("Taxi", row.getCell(1).getStringCellValue());
            assertEquals("Gasto", row.getCell(2).getStringCellValue());
            assertEquals("Transporte", row.getCell(3).getStringCellValue());
            assertEquals(CellType.NUMERIC, row.getCell(5).getCellType());
            assertEquals(125000.50, row.getCell(5).getNumericCellValue(), 0.001);
            assertEquals(CellType.NUMERIC, row.getCell(0).getCellType());
            assertNotNull(sheet.getPaneInformation());
            assertTrue(sheet.getPaneInformation().isFreezePane());
            assertEquals(CellType.FORMULA, wb.getSheet("Resumen").getRow(5).getCell(0).getCellType());
        }
    }

    @Test
    void excelRemainsEditableAndHasNoSheetProtection() throws Exception {
        byte[] bytes = service.exportXlsx(42, new TransactionFilter());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertFalse(wb.isStructureLocked());
            assertFalse(wb.getSheet("Resumen").getProtect());
            assertFalse(wb.getSheet("Movimientos").getProtect());
            var sheet = wb.getSheet("Movimientos");
            assertEquals("Taxi", sheet.getRow(4).getCell(1).getStringCellValue());
            sheet.getRow(4).getCell(1).setCellValue("Taxi editado");
            assertEquals("Taxi editado", sheet.getRow(4).getCell(1).getStringCellValue());
        }
    }

    @Test
    void maliciousDescriptionRemainsLiteralStringInExcel() throws Exception {
        tx.setDescription("=HYPERLINK(\"https://example.com\",\"click\")");
        byte[] bytes = service.exportXlsx(42, new TransactionFilter());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var cell = wb.getSheet("Movimientos").getRow(4).getCell(1);
            assertEquals(CellType.STRING, cell.getCellType());
            assertTrue(cell.getStringCellValue().startsWith("="));
        }
    }

    @Test
    void deletedCategoryDoesNotLeakNumericId() throws Exception {
        tx.setCategoryId(99999L);
        byte[] bytes = service.exportXlsx(42, new TransactionFilter());
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals("Categoría eliminada",
                    wb.getSheet("Movimientos").getRow(4).getCell(3).getStringCellValue());
        }
    }
}
