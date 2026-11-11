package com.softdevcode.ws_finflow.service;

import com.softdevcode.ws_finflow.dto.TransactionFilter;
import com.softdevcode.ws_finflow.entity.Transaction;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionCsvSecurityTest {
    @Mock EntityManager entityManager;
    @Mock TypedQuery<Transaction> query;
    @InjectMocks TransactionService service;

    private Transaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new Transaction();
        transaction.setId(7L);
        transaction.setCategoryId(3L);
        transaction.setDescription("Mercado");
        transaction.setType("expense");
        transaction.setAmount(-12.5); // Even a legacy signed amount must remain a numeric field.
        transaction.setDate(LocalDateTime.of(2026, 11, 11, 12, 0));
        transaction.setCurrency("COP");
        transaction.setStatus("completed");
        when(entityManager.createQuery(anyString(), eq(Transaction.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(transaction));
    }

    @ParameterizedTest
    @ValueSource(strings = {"=1+1", "+SUM(1,2)", "-1+1", "@SUM(1,2)",
            "  =1+1", "\t=1+1", "\r=1+1", "\n=1+1", "\uFEFF=1+1", "＝1+1"})
    void neutralizesFormulaPrefixesInAllFreeTextFields(String payload) {
        transaction.setDescription(payload);
        transaction.setType(payload); // Defend export even if legacy data predates type validation.
        transaction.setCurrency(payload);
        transaction.setStatus(payload);

        String csv = service.exportCsv(42, new TransactionFilter());
        String protectedField = "\"'" + payload.replace("\"", "\"\"") + "\"";

        assertTrue(csv.endsWith("7," + protectedField + "," + protectedField
                + ",-12.5,3,\"2026-11-11\"," + protectedField + "," + protectedField + "\n"));
        verify(query).setParameter("userId", 42);
    }

    @Test
    void quotesCommasQuotesAndNewlinesInEveryUserControlledColumn() {
        transaction.setDescription("Mercado, \"semanal\"\nsegunda línea");
        transaction.setCurrency("COP,USD\r\nforged,row");
        transaction.setStatus("completado,\"manual\"\nextra");

        String csv = service.exportCsv(42, new TransactionFilter());

        assertTrue(csv.endsWith("7,\"Mercado, \"\"semanal\"\"\nsegunda línea\",\"expense\",-12.5,3,"
                + "\"2026-11-11\",\"COP,USD\r\nforged,row\",\"completado,\"\"manual\"\"\nextra\"\n"));
    }

    @Test
    void preservesOrdinaryTextNumericAmountsDatesAndEmptyCells() {
        transaction.setCurrency(null);
        transaction.setStatus(null);
        transaction.setDescription(null);
        transaction.setDate(null);

        String csv = service.exportCsv(42, new TransactionFilter());

        assertEquals("ID,Descripción,Tipo,Monto,Categoría,Fecha,Moneda,Estado\n"
                + "7,\"\",\"expense\",-12.5,3,\"\",\"\",\"\"\n", csv);
    }
}
