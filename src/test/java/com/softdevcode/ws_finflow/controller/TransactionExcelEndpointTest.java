package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.TransactionFilter;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionExcelEndpointTest {
    @Mock TransactionService service;
    @Mock UserRepository users;
    @InjectMocks TransactionController controller;

    @Test
    void xlsxIsRegisteredOnActiveController() throws Exception {
        var method = TransactionController.class.getDeclaredMethod("exportXlsx",
                Authentication.class, String.class, Integer.class,
                Integer.class, Long.class, String.class);
        GetMapping route = method.getAnnotation(GetMapping.class);
        assertNotNull(route, "Excel download must be mapped in the live controller");
        assertArrayEquals(new String[]{"/export/xlsx"}, route.value());
    }

    @Test
    void downloadUsesAuthenticatedOwnerAndEveryFilterAndEditableXlsxMime() {
        User owner = new User();
        owner.setId(42L);
        owner.setEmail("owner@example.com");
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        byte[] workbook = new byte[]{'P', 'K', 3, 4, 1, 2};
        when(service.exportXlsx(eq(42), any(TransactionFilter.class))).thenReturn(workbook);

        Authentication auth = new UsernamePasswordAuthenticationToken(
                "owner@example.com", null);
        var response = controller.exportXlsx(auth, "expense", 11, 2026, 3L, "taxis");

        assertEquals(200, response.getStatusCode().value());
        assertSame(workbook, response.getBody());
        assertEquals(workbook.length, response.getHeaders().getContentLength());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)
                .contains("FinFlow_Movimientos.xlsx"));
        assertEquals(MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
                response.getHeaders().getContentType());

        ArgumentCaptor<TransactionFilter> filters = ArgumentCaptor.forClass(TransactionFilter.class);
        verify(service).exportXlsx(eq(42), filters.capture());
        TransactionFilter filter = filters.getValue();
        assertEquals("expense", filter.getType());
        assertEquals(11, filter.getMonth());
        assertEquals(2026, filter.getYear());
        assertEquals(3L, filter.getCategoryId());
        assertEquals("taxis", filter.getSearch());
    }
}
