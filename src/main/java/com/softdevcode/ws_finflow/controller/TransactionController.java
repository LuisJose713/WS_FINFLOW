/*package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.MonthlyStats;
import com.softdevcode.ws_finflow.dto.TransactionFilter;
import com.softdevcode.ws_finflow.dto.TransactionRequest;
import com.softdevcode.ws_finflow.dto.TransactionStats;
import com.softdevcode.ws_finflow.entity.Transaction;
import com.softdevcode.ws_finflow.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/transactions")
@CrossOrigin(origins = "*")
public class TransactionController {

    @Autowired
    private TransactionService service;

    // GET /transactions/recent — últimas 5 para el dashboard
    @GetMapping("/recent")
    public ResponseEntity<List<Transaction>> getRecent(
            @RequestHeader("X-User-Id") Integer userId) {
        return ResponseEntity.ok(service.getRecent(userId, 5));
    }

    // GET /transactions — todas con filtros opcionales
    @GetMapping
    public ResponseEntity<List<Transaction>> getFiltered(
            @RequestHeader("X-User-Id") Integer userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search) {

        TransactionFilter filter = new TransactionFilter();
        filter.setType(type);
        filter.setMonth(month);
        filter.setYear(year);
        filter.setCategoryId(categoryId);
        filter.setSearch(search);

        return ResponseEntity.ok(service.getFiltered(userId, filter));
    }

    // POST /transactions
    @PostMapping
    public ResponseEntity<Transaction> create(
            @RequestHeader("X-User-Id") Integer userId,
            @RequestBody TransactionRequest request) {
        return ResponseEntity.ok(service.create(userId, request));
    }

    // DELETE /transactions/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-User-Id") Integer userId,
            @PathVariable Long id) {
        service.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    // GET /transactions/stats
    @GetMapping("/stats")
    public ResponseEntity<TransactionStats> getStats(
            @RequestHeader("X-User-Id") Integer userId) {
        return ResponseEntity.ok(service.getStats(userId));
    }

    // GET /transactions/monthly-stats
    @GetMapping("/monthly-stats")
    public ResponseEntity<List<MonthlyStats>> getMonthlyStats(
            @RequestHeader("X-User-Id") Integer userId) {
        return ResponseEntity.ok(service.getMonthlyStats(userId));
    }

    // GET /transactions/export/csv
    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            @RequestHeader("X-User-Id") Integer userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search) {

        TransactionFilter filter = new TransactionFilter();
        filter.setType(type);
        filter.setMonth(month);
        filter.setYear(year);
        filter.setCategoryId(categoryId);
        filter.setSearch(search);

        String csv = service.exportCsv(userId, filter);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transacciones.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/years")
    public ResponseEntity<List<Integer>> getAvailableYears(
            @RequestHeader("X-User-Id") Integer userId) {
        return ResponseEntity.ok(service.getAvailableYears(userId));
    }

    // Excel real, sin delimitadores regionales: compatible con filtros y estilos.
    @GetMapping("/export/xlsx")
    public ResponseEntity<byte[]> exportXlsx(
            Authentication auth,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search) {
        TransactionFilter filter = new TransactionFilter();
        filter.setType(type);
        filter.setMonth(month);
        filter.setYear(year);
        filter.setCategoryId(categoryId);
        filter.setSearch(search);
        byte[] file = service.exportXlsx(getUserId(auth), filter);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"finflow-movimientos.xlsx\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(file);
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf(
            @RequestHeader("X-User-Id") Integer userId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long categoryId) {

        TransactionFilter filter = new TransactionFilter();
        filter.setType(type);
        filter.setMonth(month);
        filter.setYear(year);
        filter.setCategoryId(categoryId);

        byte[] pdf = service.exportPdf(userId, filter);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transacciones.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}

 */

package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.MonthlyStats;
import com.softdevcode.ws_finflow.dto.TransactionFilter;
import com.softdevcode.ws_finflow.dto.TransactionRequest;
import com.softdevcode.ws_finflow.dto.TransactionStats;
import com.softdevcode.ws_finflow.entity.Transaction;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.service.TransactionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/transactions")

public class TransactionController {

    @Autowired
    private TransactionService service;

    @Autowired
    private UserRepository userRepository;

    // Helper — evita repetir el lookup en cada método
    private Integer getUserId(Authentication auth) {
        return Math.toIntExact(userRepository.findByEmail(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado")));
    }

    @GetMapping("/recent")
    public ResponseEntity<List<Transaction>> getRecent(Authentication auth) {
        return ResponseEntity.ok(service.getRecent(getUserId(auth), 5));
    }

    @GetMapping
    public ResponseEntity<List<Transaction>> getFiltered(
            Authentication auth,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search) {

        TransactionFilter filter = new TransactionFilter();
        filter.setType(type);
        filter.setMonth(month);
        filter.setYear(year);
        filter.setCategoryId(categoryId);
        filter.setSearch(search);

        return ResponseEntity.ok(service.getFiltered(getUserId(auth), filter));
    }

    @PostMapping
    public ResponseEntity<Transaction> create(
            Authentication auth,
            @RequestBody TransactionRequest request) {
        return ResponseEntity.ok(service.create(getUserId(auth), request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Transaction> update(
            Authentication auth, @PathVariable Long id, @RequestBody TransactionRequest request) {
        return ResponseEntity.ok(service.update(getUserId(auth), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long id) {
        service.delete(getUserId(auth), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/stats")
    public ResponseEntity<TransactionStats> getStats(Authentication auth) {
        return ResponseEntity.ok(service.getStats(getUserId(auth)));
    }

    @GetMapping("/monthly-stats")
    public ResponseEntity<List<MonthlyStats>> getMonthlyStats(Authentication auth) {
        return ResponseEntity.ok(service.getMonthlyStats(getUserId(auth)));
    }

    @GetMapping("/years")
    public ResponseEntity<List<Integer>> getAvailableYears(Authentication auth) {
        return ResponseEntity.ok(service.getAvailableYears(getUserId(auth)));
    }

    @GetMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(
            Authentication auth,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search) {

        TransactionFilter filter = new TransactionFilter();
        filter.setType(type);
        filter.setMonth(month);
        filter.setYear(year);
        filter.setCategoryId(categoryId);
        filter.setSearch(search);

        String csv = service.exportCsv(getUserId(auth), filter);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transacciones.csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportPdf(
            Authentication auth,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search) {

        TransactionFilter filter = new TransactionFilter();
        filter.setType(type);
        filter.setMonth(month);
        filter.setYear(year);
        filter.setCategoryId(categoryId);
        filter.setSearch(search);

        byte[] pdf = service.exportPdf(getUserId(auth), filter);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"transacciones.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}