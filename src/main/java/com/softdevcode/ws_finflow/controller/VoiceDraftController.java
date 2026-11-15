package com.softdevcode.ws_finflow.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Spanish dictation as a financial DRAFT, never a financial write.
 * All clients must show an editable preview and require a user confirmation.
 */
@RestController
@RequestMapping("/voice")
public class VoiceDraftController {
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\d])([0-9][0-9.,]{0,17})(?:\\s*(mil|millon|millones))?(?![\\p{L}\\d])");
    private static final Pattern INCOME = Pattern.compile("\\b(recibi|ingrese|cobre|gane|ganancia|ingreso|me pagaron|me consignaron|depositaron|salario)\\b");
    private static final Pattern EXPENSE = Pattern.compile("\\b(gaste|pague|compre|gasto|compra|pago|cost[o]|me costo|salida)\\b");
    private final CurrencyPreferencesController preferences;

    public VoiceDraftController(CurrencyPreferencesController preferences) {
        this.preferences = preferences;
    }

    public record Request(String text) {}
    public record Draft(String title, Double amount, String type, String currency,
                        boolean requiresConversion, String warning) {}

    @PostMapping("/draft")
    public Draft createDraft(Authentication authentication, @RequestBody Request body) {
        // Calling the authenticated preference endpoint also ensures this is
        // the account owner; never accept an owner/user ID from the dictation.
        String code = preferences.read(authentication).get("currency");
        if (body == null || body.text() == null || body.text().isBlank() || body.text().length() > 300)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dictado inválido");
        return parse(body.text(), code);
    }

    public static Draft parse(String input, String defaultCurrency) {
        String cleaned = input.strip().replaceAll("[\\p{Cntrl}\\s]+", " ");
        String normal = Normalizer.normalize(cleaned.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        String currency = defaultCurrency != null && defaultCurrency.matches("COP|USD|EUR|GBP|MXN|BRL|CAD")
            ? defaultCurrency : "COP";
        if (normal.matches("(?s).*\\b(dolares?|usd|dollars?)\\b.*")) currency = "USD";
        else if (normal.matches("(?s).*\\b(euros?|eur)\\b.*")) currency = "EUR";
        else if (normal.matches("(?s).*\\b(pesos? colombianos?|cop)\\b.*")) currency = "COP";

        boolean income = INCOME.matcher(normal).find(), expense = EXPENSE.matcher(normal).find();
        String type = income == expense ? null : (income ? "income" : "expense");

        Matcher found = NUMBER.matcher(normal);
        Double amount = null;
        String amountToken = null;
        while (found.find()) {
            Double parsed = parseAmount(found.group(1), found.group(2));
            if (parsed == null || parsed <= 0 || parsed > 1_000_000_000_000d) continue;
            // Prefer the largest plausible number; speech may include a date.
            if (amount == null || parsed > amount) {
                amount = parsed;
                amountToken = found.group();
            }
        }

        String title = normal;
        if (amountToken != null) title = title.replaceFirst(Pattern.quote(amountToken), " ");
        title = title.replaceAll("\\b(recibi|ingrese|cobre|gane|ganancia|ingreso|me pagaron|me consignaron|depositaron|salario|gaste|pague|compre|gasto|compra|pago|costo|me costo|salida|de|por|en|un|una|el|la|los|las|pesos?|colombianos?|dolares?|usd|dollars?|euros?|eur|cop)\\b", " ")
                     .replaceAll("[^\\p{L}\\p{N} -]", " ").replaceAll("\\s+", " ").strip();
        if (title.isBlank()) title = "Movimiento por voz";
        title = title.substring(0, Math.min(120, title.length()));
        boolean requiresConversion = !"COP".equals(currency);
        String warning = amount == null ? "No reconocimos un monto; complétalo manualmente."
            : type == null ? "Confirma si es ingreso o gasto antes de registrar."
            : requiresConversion ? "Monto en " + currency + ": no se convierte ni guarda como COP automáticamente."
            : "Revisa título, monto, categoría y cuenta antes de guardar.";
        return new Draft(title, amount, type, currency, requiresConversion, warning);
    }

    private static Double parseAmount(String raw, String scale) {
        try {
            String normalized = raw;
            int commas = (int) raw.chars().filter(c -> c == ',').count();
            int dots = (int) raw.chars().filter(c -> c == '.').count();
            int last = Math.max(raw.lastIndexOf(','), raw.lastIndexOf('.'));
            if (last >= 0) {
                int digits = raw.length() - last - 1;
                boolean decimal = digits <= 2
                    || (commas > 0 && dots > 0 && raw.charAt(last) == ',');
                if (decimal) {
                    normalized = raw.substring(0, last).replaceAll("[.,]", "")
                                 + "." + raw.substring(last + 1);
                } else normalized = raw.replaceAll("[.,]", "");
            }
            double value = Double.parseDouble(normalized);
            if ("mil".equals(scale)) value *= 1000;
            if ("millon".equals(scale) || "millones".equals(scale)) value *= 1_000_000;
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException e) { return null; }
    }
}
