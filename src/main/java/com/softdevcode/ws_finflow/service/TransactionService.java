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
import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
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
    private final CategoryRepository categoryRepository;
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

    // ── Financial overview: completed COP operations only ──────────
    // Keep charts consistent with monthly budgets and account balances.
    private boolean isPostedCOP(Transaction tx) {
        if (tx == null || tx.getAmount() == null ||
                !Double.isFinite(tx.getAmount()) || tx.getAmount() <= 0) return false;
        String status = tx.getStatus();
        if (status != null && !"completed".equals(status)) return false;
        String currency = tx.getCurrency();
        if (currency != null && !currency.isBlank() && !"COP".equalsIgnoreCase(currency.trim())) return false;
        return "income".equals(tx.getType()) || "expense".equals(tx.getType());
    }

    public TransactionStats getStats(Integer userId) {
        List<Transaction> posted = repository.findByUserIdOrderByDateDesc(userId).stream()
                .filter(this::isPostedCOP).toList();
        double income = posted.stream().filter(t -> "income".equals(t.getType()))
                .mapToDouble(Transaction::getAmount).sum();
        double expenses = posted.stream().filter(t -> "expense".equals(t.getType()))
                .mapToDouble(Transaction::getAmount).sum();
        double savingsRate = income > 0 ? (income - expenses) / income * 100 : 0;

        Map<String, double[]> months = new TreeMap<>();
        Map<Long, String> names = new HashMap<>();
        for (Category category : categoryRepository.findVisibleTo(userId)) {
            if (category.getId() != null) names.put(category.getId(), category.getName());
        }
        Map<String, Double> spentByCategory = new HashMap<>();
        for (Transaction tx : posted) {
            if (tx.getDate() != null) {
                String month = tx.getDate().toLocalDate().toString().substring(0, 7);
                double[] total = months.computeIfAbsent(month, ignored -> new double[]{0, 0});
                if ("income".equals(tx.getType())) total[0] += tx.getAmount();
                else total[1] += tx.getAmount();
            }
            if ("expense".equals(tx.getType())) {
                String name = tx.getCategoryId() == null
                        ? "Sin categoría" : names.getOrDefault(tx.getCategoryId(), "Categoría eliminada");
                spentByCategory.merge(name, tx.getAmount(), Double::sum);
            }
        }

        List<Map<String, Object>> monthly = months.entrySet().stream()
                .map(entry -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("month", entry.getKey());
                    row.put("income", entry.getValue()[0]);
                    row.put("expenses", entry.getValue()[1]);
                    return row;
                }).toList();

        List<Map<String, Object>> categories = new ArrayList<>();
        for (Map.Entry<String, Double> entry : spentByCategory.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", entry.getKey());
            row.put("amount", entry.getValue());
            row.put("percentage", expenses > 0 ? entry.getValue() / expenses * 100 : 0.0);
            categories.add(row);
        }

        return new TransactionStats(income, expenses, income - expenses,
                savingsRate, monthly, categories);
    }

    public List<MonthlyStats> getMonthlyStats(Integer userId) {
        Map<String, double[]> map = new TreeMap<>();
        for (Transaction tx : repository.findByUserIdOrderByDateDesc(userId)) {
            if (!isPostedCOP(tx) || tx.getDate() == null) continue;
            String month = tx.getDate().getYear() + "-" +
                    String.format("%02d", tx.getDate().getMonthValue());
            double[] total = map.computeIfAbsent(month, ignored -> new double[]{0, 0});
            if ("income".equals(tx.getType())) total[0] += tx.getAmount();
            else total[1] += tx.getAmount();
        }
        return map.entrySet().stream().map(e ->
                new MonthlyStats(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
    }

    // ── Exportar CSV ──────────────────────────────────────
    public String exportCsv(Integer userId, TransactionFilter filter) {
        List<Transaction> list = getFiltered(userId, filter);
        StringBuilder sb = new StringBuilder();
        sb.append("ID,Descripción,Tipo,Monto,Categoría,Fecha,Moneda,Estado\n");

        for (Transaction t : list) {
            sb.append(t.getId()).append(",")
                    .append(csvText(t.getDescription())).append(",")
                    .append(csvText(t.getType())).append(",")
                    .append(t.getAmount()).append(",")
                    .append(t.getCategoryId()).append(",")
                    .append(csvText(t.getDate() != null ? t.getDate().toLocalDate().toString() : null)).append(",")
                    .append(csvText(t.getCurrency())).append(",")
                    .append(csvText(t.getStatus())).append("\n");
        }
        return sb.toString();
    }

    // ── Native Excel report: real cells, formatting and category names ────────
    public byte[] exportXlsx(Integer userId, TransactionFilter filter) {
        return FinFlowExcelReport.build(getFiltered(userId, filter),
                visibleCategoryNames(userId), buildSubtitle(filter));
    }

    private Map<Long, String> visibleCategoryNames(Integer userId) {
        Map<Long, String> result = new HashMap<>();
        for (Category c : categoryRepository.findVisibleTo(userId)) {
            if (c.getId() != null && c.getName() != null) result.put(c.getId(), c.getName());
        }
        return result;
    }

    // ── Professional branded PDF report ────────────────────────────────────
    public byte[] exportPdf(Integer userId, TransactionFilter filter) {
        List<Transaction> list = getFiltered(userId, filter);
        Map<Long, String> names = visibleCategoryNames(userId);
        double income = list.stream().filter(this::isPostedCOP)
                .filter(t -> "income".equals(t.getType())).mapToDouble(Transaction::getAmount).sum();
        double expenses = list.stream().filter(this::isPostedCOP)
                .filter(t -> "expense".equals(t.getType())).mapToDouble(Transaction::getAmount).sum();
        DeviceRgb navy = new DeviceRgb(15, 23, 42);
        DeviceRgb ink = new DeviceRgb(32, 43, 63);
        DeviceRgb blue = new DeviceRgb(91, 110, 245);
        DeviceRgb muted = new DeviceRgb(100, 116, 139);
        DeviceRgb green = new DeviceRgb(22, 163, 74);
        DeviceRgb red = new DeviceRgb(220, 38, 38);
        DeviceRgb pale = new DeviceRgb(242, 246, 253);
        DeviceRgb white = new DeviceRgb(255, 255, 255);
        java.text.NumberFormat money = java.text.NumberFormat.getNumberInstance(
                java.util.Locale.forLanguageTag("es-CO"));
        money.setMaximumFractionDigits(2);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // Open without a password, but deny writing/annotations/assembly/copy
            // in PDF readers that honor document permission flags.
            // The owner key is randomly generated by iText and never distributed.
            PdfDocument pdf = new PdfDocument(new PdfWriter(out,
                    new com.itextpdf.kernel.pdf.WriterProperties().setStandardEncryption(
                            null, null,
                            com.itextpdf.kernel.pdf.EncryptionConstants.ALLOW_SCREENREADERS,
                            com.itextpdf.kernel.pdf.EncryptionConstants.ENCRYPTION_AES_256)));
            pdf.setDefaultPageSize(com.itextpdf.kernel.geom.PageSize.A4.rotate());
            Document doc = new Document(pdf);
            doc.setMargins(32, 38, 34, 38);

            Table masthead = new Table(UnitValue.createPercentArray(new float[]{4, 2}))
                    .useAllAvailableWidth();
            masthead.addCell(new Cell()
                    .add(new Paragraph("FinFlow").setBold().setFontSize(25)
                            .setFontColor(white).setMarginBottom(1))
                    .add(new Paragraph("FINANCIAL INTELLIGENCE  /  INFORME PERSONAL")
                            .setFontSize(8).setFontColor(new DeviceRgb(195, 208, 255)))
                    .setBackgroundColor(navy).setPadding(16)
                    .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER));
            masthead.addCell(new Cell()
                    .add(new Paragraph("REPORTE DE MOVIMIENTOS  /  SOLO LECTURA").setFontSize(11).setBold()
                            .setFontColor(white).setTextAlignment(TextAlignment.RIGHT))
                    .add(new Paragraph("Emitido  " +
                            java.time.LocalDate.now(java.time.ZoneId.of("America/Bogota")).format(fmt))
                            .setFontSize(9).setFontColor(new DeviceRgb(195, 208, 255))
                            .setTextAlignment(TextAlignment.RIGHT))
                    .setBackgroundColor(navy).setPadding(16)
                    .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER));
            doc.add(masthead);
            doc.add(new Paragraph("VISIÓN FINANCIERA")
                    .setFontSize(9).setBold().setFontColor(blue)
                    .setMarginTop(18).setMarginBottom(1));
            doc.add(new Paragraph("Tus movimientos, con claridad.")
                    .setFontSize(17).setBold().setFontColor(ink)
                    .setMarginBottom(2));
            doc.add(new Paragraph(buildSubtitle(filter) + "  /  " + list.size() + " registros")
                    .setFontSize(9).setFontColor(muted).setMarginBottom(12));

            Table kpis = new Table(UnitValue.createPercentArray(new float[]{1, 1, 1, 1}))
                    .useAllAvailableWidth();
            kpis.addCell(reportKpi("INGRESOS", "$ " + money.format(income), green, pale));
            kpis.addCell(reportKpi("GASTOS", "$ " + money.format(expenses), red, pale));
            kpis.addCell(reportKpi("BALANCE", "$ " + money.format(income - expenses), blue, pale));
            kpis.addCell(reportKpi("MOVIMIENTOS", Integer.toString(list.size()), ink, pale));
            doc.add(kpis);
            doc.add(new Paragraph("Resumen: movimientos completados en COP. No representa saldo bancario.")
                    .setFontSize(8).setFontColor(muted).setMarginTop(5).setMarginBottom(14));
            doc.add(new Paragraph("DETALLE DE TRANSACCIONES")
                    .setFontSize(10).setBold().setFontColor(navy).setMarginBottom(7));

            Table detail = new Table(UnitValue.createPercentArray(
                    new float[]{1.25f, 3.3f, 2.2f, 1.15f, 1f, 1.6f, 1.6f})).useAllAvailableWidth();
            for (String title : new String[]{"Fecha", "Concepto", "Categoría", "Tipo",
                    "Moneda", "Ingreso", "Gasto"}) {
                detail.addHeaderCell(new Cell()
                        .add(new Paragraph(title).setFontSize(8).setBold().setFontColor(white))
                        .setBackgroundColor(navy).setPadding(8)
                        .setBorder(com.itextpdf.layout.borders.Border.NO_BORDER));
            }
            for (int i = 0; i < list.size(); i++) {
                Transaction tx = list.get(i);
                boolean isIncome = "income".equals(tx.getType());
                boolean isExpense = "expense".equals(tx.getType());
                DeviceRgb bg = i % 2 == 0 ? white : pale;
                String amount = tx.getAmount() != null && Double.isFinite(tx.getAmount())
                        ? money.format(Math.abs(tx.getAmount())) : "—";
                detail.addCell(rowCell(tx.getDate() == null ? "—" : tx.getDate().format(fmt),
                        bg, muted, TextAlignment.LEFT));
                detail.addCell(rowCell(tx.getDescription(), bg, ink, TextAlignment.LEFT));
                detail.addCell(rowCell(FinFlowExcelReport.categoryName(tx, names),
                        bg, ink, TextAlignment.LEFT));
                detail.addCell(rowCell(isIncome ? "Ingreso" : isExpense ? "Gasto" : "—",
                        bg, ink, TextAlignment.LEFT));
                detail.addCell(rowCell(tx.getCurrency() == null ? "COP" : tx.getCurrency(),
                        bg, muted, TextAlignment.LEFT));
                detail.addCell(rowCell(isIncome ? amount : "—", bg, green, TextAlignment.RIGHT));
                detail.addCell(rowCell(isExpense ? amount : "—", bg, red, TextAlignment.RIGHT));
            }
            if (list.isEmpty()) {
                doc.add(new Paragraph("No hay movimientos para los filtros seleccionados.")
                        .setTextAlignment(TextAlignment.CENTER).setFontSize(10)
                        .setFontColor(muted).setMarginTop(16));
            } else {
                doc.add(detail);
            }
            doc.add(new Paragraph("FINFLOW  /  DOCUMENTO PERSONAL Y CONFIDENCIAL")
                    .setFontSize(8).setBold().setFontColor(blue).setMarginTop(18));
            doc.add(new Paragraph("Este informe contiene movimientos registrados por el usuario. " +
                    "Comprueba los importes con tu entidad financiera antes de tomar decisiones.")
                    .setFontSize(8).setFontColor(muted));
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Error al generar PDF FinFlow", e);
        }
    }

    private Cell reportKpi(String label, String value, DeviceRgb accent, DeviceRgb background) {
        return new Cell().add(new Paragraph(label).setFontSize(8).setBold()
                        .setFontColor(new DeviceRgb(100, 116, 139)).setMarginBottom(5))
                .add(new Paragraph(value).setFontSize(15).setBold().setFontColor(accent))
                .setPadding(11).setBackgroundColor(background)
                .setBorder(new SolidBorder(new DeviceRgb(226, 232, 240), 1));
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

    /**
     * CSV quoting preserves field boundaries, but does not stop spreadsheet formulas.
     * Prefix potentially executable text with an apostrophe before RFC-style quoting.
     * Numeric identifiers and amounts are serialized separately and stay numeric.
     */
    private String csvText(String value) {
        if (value == null) return "\"\"";
        int firstContent = 0;
        while (firstContent < value.length()) {
            int codePoint = value.codePointAt(firstContent);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                    && !Character.isISOControl(codePoint)
                    && Character.getType(codePoint) != Character.FORMAT) break;
            firstContent += Character.charCount(codePoint);
        }
        boolean startsWithControl = !value.isEmpty() && Character.isISOControl(value.codePointAt(0));
        boolean startsWithFormula = firstContent < value.length()
                && "=+-@＝＋－＠".indexOf(value.codePointAt(firstContent)) >= 0;
        String safe = startsWithControl || startsWithFormula ? "'" + value : value;
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
