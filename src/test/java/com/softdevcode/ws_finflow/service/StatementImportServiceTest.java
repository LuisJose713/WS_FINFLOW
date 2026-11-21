package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.entity.Transaction;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import com.softdevcode.ws_finflow.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StatementImportServiceTest {
    private final TransactionRepository repository = mock(TransactionRepository.class);
    private final TransactionService transactions = mock(TransactionService.class);
    private final CategoryRepository categories = mock(CategoryRepository.class);
    private final FinancialAccountService accounts = mock(FinancialAccountService.class);
    private final EntityManager manager = mock(EntityManager.class);
    private StatementImportService importer;

    @BeforeEach
    void setup() {
        importer = new StatementImportService(repository, transactions, categories, accounts, manager);
        when(repository.findAllByUserId(4)).thenReturn(List.of());
        when(categories.findVisibleTo(4)).thenReturn(List.of());
    }

    private MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "banco.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8));
    }

    @Test void csvDebitCreditPreviewDoesNotWrite() {
        String source = "Fecha;Descripción;Débito;Crédito\n" +
            "20/11/2026;Mercado;185.000,00;\n" +
            "21/11/2026;Nómina;;2.800.000,00\n";
        StatementImportService.Preview preview = importer.preview(4, 12L, csv(source));
        assertEquals(2, preview.rows().size());
        assertEquals("expense", preview.rows().get(0).type());
        assertEquals(new BigDecimal("185000.00"), preview.rows().get(0).amount());
        assertEquals("income", preview.rows().get(1).type());
        assertEquals("ready", preview.rows().get(0).status());
        verifyNoInteractions(transactions);
    }

    @Test void unsignedPositiveAmountRequiresReview() {
        StatementImportService.Preview preview = importer.preview(4, 12L,
            csv("Fecha,Concepto,Monto\n2026-11-20,Compra local,50000\n"));
        assertEquals(1, preview.rows().size());
        assertNull(preview.rows().get(0).type());
        assertEquals("review", preview.rows().get(0).status());
    }

    @Test void detectsExistingExpenseInSameAccount() {
        Transaction existing = new Transaction();
        existing.setAccountId(12L);
        existing.setDate(LocalDate.of(2026, 11, 20).atStartOfDay());
        existing.setAmount(185000.0);
        existing.setType("expense");
        existing.setDescription("Mercado");
        when(repository.findAllByUserId(4)).thenReturn(List.of(existing));
        StatementImportService.Preview preview = importer.preview(4, 12L,
            csv("Fecha;Descripción;Débito;Crédito\n20/11/2026;Mercado;185000;\n"));
        assertEquals("duplicate", preview.rows().get(0).status());
    }

    @Test void excelPreviewIsSupported() throws Exception {
        byte[] data;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet();
            var headers = sheet.createRow(0);
            headers.createCell(0).setCellValue("Fecha");
            headers.createCell(1).setCellValue("Descripción");
            headers.createCell(2).setCellValue("Débito");
            var item = sheet.createRow(1);
            item.createCell(0).setCellValue("20/11/2026");
            item.createCell(1).setCellValue("Almuerzo");
            item.createCell(2).setCellValue(25000);
            wb.write(buffer);
            data = buffer.toByteArray();
        }
        var file = new MockMultipartFile("file", "banco.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", data);
        var preview = importer.preview(4, 12L, file);
        assertEquals("XLSX", preview.format());
        assertEquals("expense", preview.rows().get(0).type());
    }

    @Test void requiresExplicitConfirmationAndSkipsRepeat() {
        when(manager.find(eq(User.class), eq(4L), eq(LockModeType.PESSIMISTIC_WRITE))).thenReturn(new User());
        var row = new StatementImportService.Approved(2, LocalDate.of(2026,11,20),
            "Mercado", new BigDecimal("185000.00"), "expense", null, 12L, "COP", true);
        assertThrows(ResponseStatusException.class,
            () -> importer.confirm(4, new StatementImportService.Confirmation(false, List.of(row))));
        var duplicate = new StatementImportService.Approved(3, row.date(), row.description(), row.amount(),
            row.type(), null, 12L, "COP", true);
        var result = importer.confirm(4, new StatementImportService.Confirmation(true, List.of(row, duplicate)));
        assertEquals(1, result.created());
        assertEquals(1, result.duplicatesSkipped());
        verify(transactions, times(1)).create(eq(4), any());
    }

    @Test void rejectsForeignCurrencyAndTooLargeUploads() {
        assertThrows(ResponseStatusException.class, () -> importer.preview(4,12L,
            new MockMultipartFile("file","banco.exe","application/octet-stream",new byte[]{1,2,3})));
        byte[] tooLarge = new byte[3*1024*1024+1];
        assertThrows(ResponseStatusException.class, () -> importer.preview(4,12L,
            new MockMultipartFile("file","banco.csv","text/csv",tooLarge)));
    }
}
