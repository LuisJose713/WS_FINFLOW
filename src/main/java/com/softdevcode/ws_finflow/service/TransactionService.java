package com.softdevcode.ws_finflow.service;

import com.itextpdf.kernel.colors.ColorConstants;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import com.softdevcode.ws_finflow.dto.MonthlyStats;
import com.softdevcode.ws_finflow.dto.TransactionFilter;
import com.softdevcode.ws_finflow.dto.TransactionRequest;
import com.softdevcode.ws_finflow.dto.TransactionStats;
import com.softdevcode.ws_finflow.entity.Transaction;
import com.softdevcode.ws_finflow.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TransactionService {

    private final TransactionRepository repository;
    private final EntityManager          em;
    private final CategoryAccessValidator categoryAccessValidator;
    private final FinancialAccountService financialAccounts;

    // ── Todas ─────────────────────────────────────────────
    public List<Transaction> getAll(Integer userId) {
        return repository.findByUserIdOrderByDateDesc(userId);
    }

    // ── Últimas N ─────────────────────────────────────────
    public List<Transaction> getRecent(Integer userId, int limit) {
        return repository.findByUserIdOrderByDateDesc(userId, PageRequest.of(0, limit));
    }

    // ── Con filtros dinámicos ─────────────────────────────
    public List<Transaction> getFiltered(Integer userId, TransactionFilter filter) {
        StringBuilder jpql = new StringBuilder(
                "SELECT t FROM Transaction t WHERE t.userId = :userId");

        if (filter.getType()       != null) jpql.append(" AND t.type = :type");
        if (filter.getMonth()      != null) jpql.append(" AND MONTH(t.date) = :month");
        if (filter.getYear()       != null) jpql.append(" AND YEAR(t.date) = :year");
        if (filter.getCategoryId() != null) jpql.append(" AND t.categoryId = :categoryId");
        if (filter.getSearch()     != null) jpql.append(" AND LOWER(t.description) LIKE :search");

        jpql.append(" ORDER BY t.date DESC");

        TypedQuery<Transaction> query = em.createQuery(jpql.toString(), Transaction.class);
        query.setParameter("userId", userId);

        if (filter.getType()       != null) query.setParameter("type",       filter.getType());
        if (filter.getMonth()      != null) query.setParameter("month",      filter.getMonth());
        if (filter.getYear()       != null) query.setParameter("year",       filter.getYear());
        if (filter.getCategoryId() != null) query.setParameter("categoryId", filter.getCategoryId());
        if (filter.getSearch()     != null) query.setParameter("search",     "%" + filter.getSearch().toLowerCase() + "%");

        return query.getResultList();
    }

    // ── Años disponibles ──────────────────────────────────
    public List<Integer> getAvailableYears(Integer userId) {
        return repository.findByUserIdOrderByDateDesc(userId).stream()
                .filter(t -> t.getDate() != null)
                .map(t -> t.getDate().getYear())
                .distinct()
                .sorted(Comparator.reverseOrder())
                .collect(Collectors.toList());
    }

    // ── Crear ─────────────────────────────────────────────
    @Transactional
    public Transaction create(Integer userId, TransactionRequest request) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        if (request.getAmount() == null || !Double.isFinite(request.getAmount()) || request.getAmount() <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "El monto debe ser positivo");
        }
        if (!"income".equals(request.getType()) && !"expense".equals(request.getType())) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Tipo de transacción inválido");
        }
        tx.setAmount(request.getAmount());
        tx.setType(request.getType());
        if (request.getCategoryId() != null) categoryAccessValidator.requireVisible(userId, request.getCategoryId(), request.getType());
        tx.setCategoryId(request.getCategoryId());
        tx.setDescription(request.getDescription());
        tx.setDate(request.getDate() != null
                ? request.getDate().atStartOfDay()
                : LocalDateTime.now());
        String currency=request.getCurrency()!=null?request.getCurrency():"COP";
        financialAccounts.assertOwnedAndCurrency(userId,request.getAccountId(),currency);
        tx.setAccountId(request.getAccountId());
        tx.setCurrency(currency);
        tx.setStatus(request.getStatus()     != null ? request.getStatus()   : "completed");
        tx.setCreatedAt(LocalDateTime.now());
        return repository.save(tx);
    }

    // ── Editar únicamente una transacción propia ─────────
    @Transactional
    public Transaction update(Integer userId, Long id, TransactionRequest request) {
        Transaction tx = getOwnedTransaction(userId, id);
        if (request.getAmount() != null) {
            if (!Double.isFinite(request.getAmount()) || request.getAmount() <= 0) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "El monto debe ser positivo");
            }
            tx.setAmount(request.getAmount());
        }
        if (request.getType() != null) {
            if (!"income".equals(request.getType()) && !"expense".equals(request.getType())) {
                throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Tipo de transacción inválido");
            }
            tx.setType(request.getType());
        }
        if (request.getCategoryId() != null) tx.setCategoryId(request.getCategoryId());
        if (tx.getCategoryId() != null) categoryAccessValidator.requireVisible(userId, tx.getCategoryId(), tx.getType());
        if (request.getDescription() != null) tx.setDescription(request.getDescription());
        if (request.getDate() != null) tx.setDate(request.getDate().atStartOfDay());
        if (request.getAccountId()!=null) tx.setAccountId(request.getAccountId());
        if (request.getCurrency() != null) tx.setCurrency(request.getCurrency());
        financialAccounts.assertOwnedAndCurrency(userId,tx.getAccountId(),tx.getCurrency());
        if (request.getStatus() != null) tx.setStatus(request.getStatus());
        return repository.save(tx);
    }

    // ── Eliminar solo registros del usuario autenticado ───
    @Transactional
    public void delete(Integer userId, Long id) {
        repository.delete(getOwnedTransaction(userId, id));
    }

    private Transaction getOwnedTransaction(Integer userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "Transacción no encontrada"));
    }

    // ── Stats ─────────────────────────────────────────────
    public TransactionStats getStats(Integer userId) {
        List<Transaction> transactions = repository.findByUserIdOrderByDateDesc(userId);

        double totalIncome = transactions.stream()
                .filter(t -> "income".equals(t.getType()))
                .mapToDouble(Transaction::getAmount).sum();

        double totalExpenses = transactions.stream()
                .filter(t -> "expense".equals(t.getType()))
                .mapToDouble(Transaction::getAmount).sum();

        double savingsRate = totalIncome > 0
                ? ((totalIncome - totalExpenses) / totalIncome) * 100 : 0;

        // Agrupar por mes "2026-03"
        Map<String, double[]> byMonth = new TreeMap<>();
        for (Transaction t : transactions) {
            if (t.getDate() == null) continue;
            String month = t.getDate().toLocalDate().toString().substring(0, 7);
            byMonth.putIfAbsent(month, new double[]{0.0, 0.0});
            if ("income".equals(t.getType())) byMonth.get(month)[0] += t.getAmount();
            else byMonth.get(month)[1] += t.getAmount();
        }

        List<Map<String, Object>> monthlyData = byMonth.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("month", e.getKey());
                    m.put("income", e.getValue()[0]);
                    m.put("expenses", e.getValue()[1]);
                    return m;
                })
                .collect(Collectors.toList());

        return new TransactionStats(
                totalIncome, totalExpenses,
                totalIncome - totalExpenses, savingsRate,
                monthlyData, List.of()
        );
    }
    // ── Stats mensuales ───────────────────────────────────
    public List<MonthlyStats> getMonthlyStats(Integer userId) {
        List<Transaction> all = repository.findByUserIdOrderByDateDesc(userId);
        Map<String, double[]> map = new LinkedHashMap<>();

        for (Transaction t : all) {
            String key = t.getDate().getYear() + "-" + String.format("%02d", t.getDate().getMonthValue());
            map.putIfAbsent(key, new double[]{0, 0});
            if ("income".equals(t.getType())) map.get(key)[0] += t.getAmount();
            else map.get(key)[1] += t.getAmount();
        }

        return map.entrySet().stream()
                .map(e -> new MonthlyStats(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .collect(Collectors.toList());
    }

    // ── Exportar CSV ──────────────────────────────────────
    public String exportCsv(Integer userId, TransactionFilter filter) {
        List<Transaction> list = getFiltered(userId, filter);
        StringBuilder sb = new StringBuilder();
        sb.append("ID,Descripción,Tipo,Monto,Categoría,Fecha,Moneda,Estado\n");

        for (Transaction t : list) {
            sb.append(t.getId()).append(",")
                    .append(escape(t.getDescription())).append(",")
                    .append(t.getType()).append(",")
                    .append(t.getAmount()).append(",")
                    .append(t.getCategoryId()).append(",")
                    .append(t.getDate() != null ? t.getDate().toLocalDate() : "").append(",")
                    .append(t.getCurrency()).append(",")
                    .append(t.getStatus()).append("\n");
        }
        return sb.toString();
    }

    // ── Exportar PDF ──────────────────────────────────────
    public byte[] exportPdf(Integer userId, TransactionFilter filter) {
        List<Transaction> list = getFiltered(userId, filter);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter   writer   = new PdfWriter(baos);
            PdfDocument pdfDoc   = new PdfDocument(writer);
            Document    document = new Document(pdfDoc);

            // Título
            document.add(new Paragraph("Reporte de Transacciones")
                    .setFontSize(18)
                    .setBold()
                    .setFontColor(new DeviceRgb(108, 138, 255))
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(4));

            // Subtítulo
            document.add(new Paragraph(buildSubtitle(filter))
                    .setFontSize(10)
                    .setFontColor(ColorConstants.GRAY)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(16));

            // Resumen
            double totalIncome  = list.stream().filter(t -> "income".equals(t.getType())).mapToDouble(Transaction::getAmount).sum();
            double totalExpense = list.stream().filter(t -> "expense".equals(t.getType())).mapToDouble(Transaction::getAmount).sum();
            double balance      = totalIncome - totalExpense;

            Table summary = new Table(UnitValue.createPercentArray(new float[]{1, 1, 1})).useAllAvailableWidth();
            summary.addCell(summaryCell("Total ingresos", String.format("$%,.0f", totalIncome),  new DeviceRgb(34, 197, 94)));
            summary.addCell(summaryCell("Total egresos",  String.format("$%,.0f", totalExpense), new DeviceRgb(239, 68, 68)));
            summary.addCell(summaryCell("Balance",        String.format("$%,.0f", balance),      new DeviceRgb(108, 138, 255)));
            document.add(summary);
            document.add(new Paragraph(" ").setMarginBottom(12));

            // Tabla
            Table table = new Table(UnitValue.createPercentArray(new float[]{3, 2, 2, 2, 2})).useAllAvailableWidth();

            DeviceRgb headerBg = new DeviceRgb(30, 34, 48);
            for (String h : new String[]{"Descripción", "Tipo", "Monto", "Categoría", "Fecha"}) {
                table.addHeaderCell(new Cell()
                        .add(new Paragraph(h).setBold().setFontSize(9).setFontColor(ColorConstants.WHITE))
                        .setBackgroundColor(headerBg)
                        .setPadding(6));
            }

            DateTimeFormatter fmt    = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            DeviceRgb incomeColor   = new DeviceRgb(34, 197, 94);
            DeviceRgb expenseColor  = new DeviceRgb(239, 68, 68);
            DeviceRgb rowAlt        = new DeviceRgb(245, 246, 250);

            for (int i = 0; i < list.size(); i++) {
                Transaction t    = list.get(i);
                boolean isIncome = "income".equals(t.getType());
                DeviceRgb amtColor = isIncome ? incomeColor : expenseColor;
                DeviceRgb rowBg    = i % 2 == 0 ? new DeviceRgb(255, 255, 255) : rowAlt;

                table.addCell(rowCell(t.getDescription(), rowBg, ColorConstants.BLACK, TextAlignment.LEFT));
                table.addCell(rowCell(isIncome ? "Ingreso" : "Gasto", rowBg, amtColor, TextAlignment.CENTER));
                table.addCell(rowCell(
                        (isIncome ? "+" : "-") + String.format("$%,.0f", t.getAmount()),
                        rowBg, amtColor, TextAlignment.RIGHT));
                table.addCell(rowCell(t.getCategoryId() != null ? "#" + t.getCategoryId() : "-", rowBg, ColorConstants.GRAY, TextAlignment.CENTER));
                table.addCell(rowCell(t.getDate() != null ? t.getDate().format(fmt) : "-", rowBg, ColorConstants.GRAY, TextAlignment.CENTER));
            }

            document.add(table);

            // Pie
            document.add(new Paragraph("FinFlow · Generado el " +
                    java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")))
                    .setFontSize(8)
                    .setFontColor(ColorConstants.GRAY)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginTop(16));

            document.close();
            return baos.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Error al generar PDF", e);
        }
    }

    // ── Helpers ───────────────────────────────────────────
    private Cell summaryCell(String label, String value, DeviceRgb color) {
        return new Cell()
                .add(new Paragraph(label).setFontSize(9).setFontColor(ColorConstants.GRAY))
                .add(new Paragraph(value).setFontSize(14).setBold().setFontColor(color))
                .setTextAlignment(TextAlignment.CENTER)
                .setPadding(10)
                .setBorder(new SolidBorder(color, 1));
    }

    private Cell rowCell(String text, DeviceRgb bg,
                         com.itextpdf.kernel.colors.Color textColor,
                         TextAlignment align) {
        return new Cell()
                .add(new Paragraph(text != null ? text : "-").setFontSize(9).setFontColor(textColor))
                .setBackgroundColor(bg)
                .setTextAlignment(align)
                .setPadding(5);
    }

    private String buildSubtitle(TransactionFilter filter) {
        StringBuilder sb = new StringBuilder();
        if (filter.getType()  != null) sb.append("Tipo: ").append("income".equals(filter.getType()) ? "Ingresos" : "Egresos").append("  ");
        if (filter.getMonth() != null) sb.append("Mes: ").append(filter.getMonth()).append("  ");
        if (filter.getYear()  != null) sb.append("Año: ").append(filter.getYear()).append("  ");
        return sb.isEmpty() ? "Todas las transacciones" : sb.toString().trim();
    }

    private String escape(String value) {
        if (value == null) return "";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
