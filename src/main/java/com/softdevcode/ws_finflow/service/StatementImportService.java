package com.softdevcode.ws_finflow.service;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import com.softdevcode.ws_finflow.dto.TransactionRequest;
import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.entity.Transaction;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import com.softdevcode.ws_finflow.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Statement import is opt-in. Preview is read-only and never stores the uploaded file.
 * Confirm checks ownership and duplicates again under a per-user database row lock.
 * Supported initially: COP CSV, XLSX and text-based PDF statements.
 */
@Service
@RequiredArgsConstructor
public class StatementImportService {
    private static final long MAX_FILE_BYTES = 3L * 1024 * 1024;
    private static final int MAX_ROWS = 250;
    private static final Pattern PDF_LINE = Pattern.compile(
        "^\\s*(\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{4}-\\d{2}-\\d{2})\\s+(.+?)\\s+([()+\\-\\$\\d., ]{2,24})\\s*$");
    private static final List<DateTimeFormatter> DATES = List.of(
        DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d/M/uu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d-M-uu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ISO_LOCAL_DATE
    );

    public record Draft(int rowNumber, LocalDate date, String description, BigDecimal amount,
                        String type, Long categoryId, String currency, String status, String note) {}
    public record Preview(List<Draft> rows, int ignored, String format, String notice) {}
    public record Approved(int rowNumber, LocalDate date, String description, BigDecimal amount,
                           String type, Long categoryId, Long accountId, String currency, boolean include) {}
    public record Confirmation(boolean confirmed, List<Approved> rows) {}
    public record Result(int created, int duplicatesSkipped, int excluded) {}
    private record Source(int rowNumber, String date, String description, String amount,
                          String debit, String credit, String type, String currency) {}

    private final TransactionRepository transactions;
    private final TransactionService transactionService;
    private final CategoryRepository categories;
    private final FinancialAccountService accounts;
    private final EntityManager entityManager;

    public Preview preview(Integer userId, Long accountId, MultipartFile file) {
        accounts.assertOwnedAndCurrency(userId, requireAccount(accountId), "COP");
        if (file == null || file.isEmpty() || file.getSize() > MAX_FILE_BYTES)
            throw bad("Archivo vacío o superior a 3 MB");
        String name = Optional.ofNullable(file.getOriginalFilename()).orElse("").toLowerCase(Locale.ROOT);
        final List<Source> extracted;
        final String format;
        try {
            byte[] bytes = file.getBytes();
            if (name.endsWith(".csv")) {
                format = "CSV";
                extracted = fromTable(delimited(decode(bytes)));
            } else if (name.endsWith(".xlsx")) {
                format = "XLSX";
                extracted = fromTable(excel(bytes));
            } else if (name.endsWith(".pdf")) {
                format = "PDF";
                extracted = pdf(bytes);
            } else throw bad("Formato no soportado: usa CSV, XLSX o PDF con texto");
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ResponseStatusException http) throw http;
            throw bad("No pudimos leer el extracto. Revisa el archivo o expórtalo como CSV/Excel");
        }
        List<Category> available = categories.findVisibleTo(userId);
        Set<String> existing = new HashSet<>();
        for (Transaction tx : transactions.findAllByUserId(userId))
            if (Objects.equals(tx.getAccountId(), accountId)) existing.add(key(tx));
        List<Draft> drafts = new ArrayList<>();
        int ignored = 0;
        for (Source src : extracted) {
            if (drafts.size() >= MAX_ROWS) { ignored++; continue; }
            LocalDate date = date(src.date());
            String description = tidy(src.description());
            BigDecimal raw = value(src.amount());
            BigDecimal debit = value(src.debit());
            BigDecimal credit = value(src.credit());
            String type = kind(src.type(), description, src.amount(), debit, credit);
            BigDecimal amount = debit != null && debit.signum() != 0 ? debit.abs()
                : credit != null && credit.signum() != 0 ? credit.abs()
                : raw == null ? null : raw.abs();
            if (date == null || amount == null || amount.signum() <= 0 ||
                amount.compareTo(new BigDecimal("1000000000000")) > 0 ||
                description.isBlank() || description.length() > 200 ||
                normalize(description).matches(".*\\b(saldo (anterior|final)|total saldo)\\b.*")) {
                ignored++;
                continue;
            }
            String currency = Optional.ofNullable(src.currency()).filter(s -> !s.isBlank())
                .map(s -> normalize(s).contains("usd") ? "USD" : "COP").orElse("COP");
            Long categoryId = suggestCategory(available, type, description);
            String status = "ready", note = "";
            if (!"COP".equals(currency)) {
                status = "review";
                note = "Solo se importan cuentas en COP. No se convierte moneda.";
            } else if (type == null) {
                status = "review";
                note = "Confirma si es ingreso o gasto.";
            } else if (isPotentialTransfer(description)) {
                status = "transfer";
                note = "Posible transferencia: verifica que no sea entre tus propias cuentas.";
            }
            if (type != null && existing.contains(key(date, amount, type, accountId, description))) {
                status = "duplicate";
                note = "Posible duplicado: ya existe un movimiento igual en esta cuenta o archivo.";
            }
            if (type != null) existing.add(key(date, amount, type, accountId, description));
            drafts.add(new Draft(src.rowNumber(), date, description, amount, type, categoryId, currency, status, note));
        }
        if (drafts.isEmpty())
            throw bad("No encontramos movimientos válidos. El archivo debe incluir fecha, descripción y monto.");
        return new Preview(drafts, ignored, format,
            "Revisa cada registro. No guardamos el archivo ni importamos transferencias automáticamente.");
    }

    @Transactional
    public Result confirm(Integer userId, Confirmation input) {
        if (input == null || !input.confirmed() || input.rows() == null ||
            input.rows().isEmpty() || input.rows().size() > MAX_ROWS)
            throw bad("Se requiere confirmar entre 1 y 250 movimientos");
        // Serializes simultaneous imports for the same owner, including retries.
        if (entityManager.find(User.class, userId.longValue(), LockModeType.PESSIMISTIC_WRITE) == null)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        Set<String> existing = new HashSet<>();
        for (Transaction tx : transactions.findAllByUserId(userId)) existing.add(key(tx));
        Set<Integer> rowNumbers = new HashSet<>();
        int created = 0, duplicates = 0, excluded = 0;
        for (Approved row : input.rows()) {
            if (row == null || row.rowNumber() < 1 || !rowNumbers.add(row.rowNumber()))
                throw bad("Hay filas repetidas o inválidas");
            if (!row.include()) { excluded++; continue; }
            requireAccount(row.accountId());
            if (row.date() == null || row.description() == null || row.description().isBlank() ||
                row.description().length() > 200 || row.amount() == null ||
                row.amount().signum() <= 0 || row.amount().scale() > 2 ||
                row.amount().compareTo(new BigDecimal("1000000000000")) > 0 ||
                (!"income".equals(row.type()) && !"expense".equals(row.type())) ||
                !"COP".equals(row.currency()))
                throw bad("Revisa fecha, monto, tipo y moneda de las filas seleccionadas");
            String fingerprint = key(row.date(), row.amount(), row.type(), row.accountId(), row.description());
            if (existing.contains(fingerprint)) { duplicates++; continue; }
            TransactionRequest request = new TransactionRequest();
            request.setDate(row.date());
            request.setDescription(tidy(row.description()));
            request.setAmount(row.amount().doubleValue());
            request.setType(row.type());
            request.setAccountId(row.accountId());
            request.setCategoryId(row.categoryId());
            request.setCurrency("COP");
            request.setStatus("completed");
            // Reuses account ownership, currency and category validation.
            transactionService.create(userId, request);
            existing.add(fingerprint);
            created++;
        }
        return new Result(created, duplicates, excluded);
    }

    private static Long requireAccount(Long account) {
        if (account == null || account <= 0) throw bad("Selecciona una billetera para importar");
        return account;
    }
    private static ResponseStatusException bad(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
    private static String tidy(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
    private static String normalize(String s) {
        return Normalizer.normalize(tidy(s).toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
    }
    private static String key(Transaction t) {
        return key(t.getDate() == null ? null : t.getDate().toLocalDate(),
            t.getAmount() == null ? null : BigDecimal.valueOf(t.getAmount()),
            t.getType(), t.getAccountId(), t.getDescription());
    }
    private static String key(LocalDate date, BigDecimal amount, String type, Long account, String description) {
        return date + "|" + (amount == null ? "" : amount.setScale(2, RoundingMode.HALF_UP).toPlainString()) +
            "|" + type + "|" + account + "|" + normalize(description).replaceAll("[^a-z0-9]+", "");
    }
    private static LocalDate date(String s) {
        if (s == null) return null;
        String v = s.trim().split("\\s+")[0];
        for (DateTimeFormatter fmt : DATES) {
            try {
                LocalDate parsed = LocalDate.parse(v, fmt);
                if (parsed.getYear() >= 2000 && !parsed.isAfter(LocalDate.now().plusDays(1))) return parsed;
            } catch (DateTimeParseException ignored) { }
        }
        return null;
    }
    private static BigDecimal value(String input) {
        if (input == null || input.isBlank() || input.trim().equals("-")) return null;
        String raw = input.trim().replaceAll("(?i)COP|\\$|\\s", "");
        boolean negative = raw.startsWith("-") || (raw.startsWith("(") && raw.endsWith(")"));
        raw = raw.replaceAll("[()\\-+]", "");
        if (!raw.matches("[0-9][0-9.,]*")) return null;
        int comma = raw.lastIndexOf(','), dot = raw.lastIndexOf('.');
        int split = Math.max(comma, dot);
        if (split >= 0) {
            int tail = raw.length() - split - 1;
            boolean decimal = tail >= 1 && tail <= 2;
            if (decimal) raw = raw.substring(0, split).replaceAll("[.,]", "") + "." + raw.substring(split + 1);
            else raw = raw.replaceAll("[.,]", "");
        }
        try {
            BigDecimal amount = new BigDecimal(raw).setScale(2, RoundingMode.UNNECESSARY);
            return negative ? amount.negate() : amount;
        } catch (NumberFormatException | ArithmeticException e) { return null; }
    }
    private static String kind(String stated, String description, String rawAmount, BigDecimal debit, BigDecimal credit) {
        if (debit != null && debit.signum() != 0) return "expense";
        if (credit != null && credit.signum() != 0) return "income";
        String text = normalize(stated);
        if (text.matches(".*(debito|cargo|egreso|retiro|compra|gasto|expense).*")) return "expense";
        if (text.matches(".*(credito|abono|ingreso|deposito|income).*")) return "income";
        if (rawAmount != null && (rawAmount.trim().startsWith("-") || rawAmount.trim().startsWith("(")))
            return "expense";
        if (normalize(description).matches(".*\\b(nomina|salario|consignacion recibida|abono de nomina)\\b.*"))
            return "income";
        // Positive unsigned entries are not necessarily income: ask user.
        return null;
    }
    private static boolean isPotentialTransfer(String d) {
        return normalize(d).matches(".*\\b(transferencia|traslado|traspaso|pse)\\b.*");
    }
    private static Long suggestCategory(List<Category> available, String type, String description) {
        if (type == null) return null;
        String normal = normalize(description);
        for (Category c : available) {
            if (!("income".equals(type) ? "Income" : "Expense").equalsIgnoreCase(c.getType())) continue;
            String n = normalize(c.getName());
            if (n.length() >= 5 && normal.contains(n)) return c.getId();
        }
        return null;
    }
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().replace("\uFEFF", "");
        } catch (CharacterCodingException e) {
            return new String(bytes, java.nio.charset.Charset.forName("windows-1252")).replace("\uFEFF", "");
        }
    }
    private static List<List<String>> delimited(String text) {
        String first = text.lines().findFirst().orElse("");
        char separator = ';';
        if (first.chars().filter(c -> c == '\t').count() >
            Math.max(first.chars().filter(c -> c == ',').count(), first.chars().filter(c -> c == ';').count()))
            separator = '\t';
        else if (first.chars().filter(c -> c == ',').count() > first.chars().filter(c -> c == ';').count())
            separator = ',';
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == separator && !quoted) {
                row.add(field.toString()); field.setLength(0);
            } else if ((c == '\n' || c == '\r') && !quoted) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(field.toString()); field.setLength(0);
                if (row.stream().anyMatch(x -> !x.isBlank())) rows.add(row);
                row = new ArrayList<>();
                if (rows.size() > MAX_ROWS + 101) throw bad("Máximo 250 movimientos por archivo");
            } else field.append(c);
        }
        if (quoted) throw bad("CSV con comillas sin cerrar");
        row.add(field.toString());
        if (row.stream().anyMatch(x -> !x.isBlank())) rows.add(row);
        return rows;
    }
    private static List<List<String>> excel(byte[] bytes) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) return rows;
            DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("es-CO"));
            for (Row row : workbook.getSheetAt(0)) {
                List<String> cells = new ArrayList<>();
                int max = Math.min(35, Math.max(0, row.getLastCellNum()));
                for (int c = 0; c < max; c++) cells.add(
                    row.getCell(c) == null ? "" : formatter.formatCellValue(row.getCell(c)));
                rows.add(cells);
                if (rows.size() > MAX_ROWS + 101) throw bad("Máximo 250 movimientos por archivo");
            }
        }
        return rows;
    }
    private static int column(List<String> names, String... terms) {
        for (int i = 0; i < names.size(); i++) {
            String h = normalize(names.get(i)).replaceAll("[^a-z0-9]", "");
            for (String term : terms) if (h.equals(term) || h.startsWith(term)) return i;
        }
        return -1;
    }
    private static String at(List<String> fields, int i) {
        return i < 0 || i >= fields.size() ? "" : fields.get(i).trim();
    }
    private static List<Source> fromTable(List<List<String>> table) {
        if (table.isEmpty()) throw bad("El archivo no contiene filas");
        int header = -1, date = -1, desc = -1, amount = -1, debit = -1, credit = -1, type = -1, currency = -1;
        for (int i = 0; i < Math.min(15, table.size()); i++) {
            List<String> columns = table.get(i);
            int d = column(columns, "fecha", "date");
            int n = column(columns, "descripcion", "concepto", "detalle", "comercio", "establecimiento", "movimiento", "referencia");
            int a = column(columns, "valor", "monto", "importe", "amount");
            int db = column(columns, "debito", "retiro", "cargo", "egreso");
            int cr = column(columns, "credito", "abono", "deposito", "ingreso");
            if (d >= 0 && n >= 0 && (a >= 0 || db >= 0 || cr >= 0)) {
                header = i; date = d; desc = n; amount = a; debit = db; credit = cr;
                type = column(columns, "tipo", "naturaleza"); currency = column(columns, "moneda", "currency");
                break;
            }
        }
        if (header < 0) throw bad("Faltan columnas: Fecha, Descripción y Valor (o Débito/Crédito)");
        List<Source> extracted = new ArrayList<>();
        for (int i = header + 1; i < table.size(); i++) {
            List<String> f = table.get(i);
            if (f.stream().allMatch(String::isBlank)) continue;
            extracted.add(new Source(i + 1, at(f, date), at(f, desc), at(f, amount), at(f, debit),
                at(f, credit), at(f, type), at(f, currency)));
        }
        return extracted;
    }
    private static List<Source> pdf(byte[] bytes) throws IOException {
        List<Source> rows = new ArrayList<>();
        try (PdfDocument pdf = new PdfDocument(new PdfReader(new ByteArrayInputStream(bytes)))) {
            if (pdf.getNumberOfPages() > 20) throw bad("PDF de máximo 20 páginas");
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                String text = PdfTextExtractor.getTextFromPage(pdf.getPage(page));
                for (String line : text.split("\\R")) {
                    Matcher m = PDF_LINE.matcher(line);
                    if (m.matches()) rows.add(new Source(rows.size() + 1, m.group(1),
                        m.group(2), m.group(3), "", "", "", "COP"));
                    if (rows.size() > MAX_ROWS + 100) throw bad("Máximo 250 movimientos por archivo");
                }
            }
        }
        return rows;
    }
}
