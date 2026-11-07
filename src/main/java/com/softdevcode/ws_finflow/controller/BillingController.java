package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.service.BillingPricingRules;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Public read-only catalog and ADMIN-only configuration.
 * IMPORTANT: there is intentionally NO customer-facing entitlement activation
 * or checkout endpoint until gateway signatures, replay protection and settlement
 * are verified server-side. All offers are informative, not proof of purchase.
 */
@RestController
@RequiredArgsConstructor
public class BillingController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private static final Pattern PROMO_CODE = Pattern.compile("[A-Z0-9_-]{4,32}");

    public record Plan(String code, String title, String description, long priceCop, boolean active, int sortOrder) {}
    public record Promotion(String code, String title, String planCode, String discountType,
                            long discountValue, Instant startsAt, Instant endsAt,
                            Integer maxRedemptions, int redemptionCount, boolean active, boolean published) {}
    public record Offer(String code, String title, String description, long originalCop,
                        long priceCop, String promoCode, String promoTitle,
                        int savingsPercent, boolean checkoutEnabled) {}
    public record Catalog(String currency, boolean checkoutEnabled, List<Offer> offers) {}
    public record PlanUpdate(String title, String description, Long priceCop, Boolean active, Integer sortOrder) {}
    public record PromotionInput(String code, String title, String planCode, String discountType,
                                 Long discountValue, Instant startsAt, Instant endsAt,
                                 Integer maxRedemptions, Boolean active, Boolean published) {}
    public record GrantInput(Long userId, String planCode, String reason) {}

    private List<Plan> plans() {
        return jdbc.query("""
            SELECT code,title,description,price_cop,is_active,sort_order
            FROM billing_plans ORDER BY sort_order,code
            """, (rs,n) -> new Plan(rs.getString("code"), rs.getString("title"),
                    rs.getString("description"), rs.getLong("price_cop"),
                    rs.getBoolean("is_active"), rs.getInt("sort_order")));
    }

    private List<Promotion> promotions() {
        return jdbc.query("""
            SELECT code,title,plan_code,discount_type,discount_value,
                   starts_at,ends_at,max_redemptions,redemption_count,is_active,is_public
            FROM billing_promotions ORDER BY created_at DESC,code
            """, (rs,n) -> new Promotion(rs.getString("code"), rs.getString("title"),
                    rs.getString("plan_code"), rs.getString("discount_type"),
                    rs.getLong("discount_value"), rs.getTimestamp("starts_at").toInstant(),
                    rs.getTimestamp("ends_at").toInstant(),
                    (Integer) rs.getObject("max_redemptions"), rs.getInt("redemption_count"),
                    rs.getBoolean("is_active"), rs.getBoolean("is_public")));
    }

    private Plan plan(String code) {
        return plans().stream().filter(p -> p.code().equals(code)).findFirst()
                .orElseThrow(() -> bad("Plan no encontrado"));
    }

    private boolean validNow(Promotion promotion, Instant now) {
        return promotion.active() && !now.isBefore(promotion.startsAt()) &&
                now.isBefore(promotion.endsAt()) &&
                (promotion.maxRedemptions() == null ||
                    promotion.redemptionCount() < promotion.maxRedemptions());
    }

    private Offer makeOffer(Plan plan, Promotion promo) {
        long price = promo == null ? plan.priceCop()
                : BillingPricingRules.discountedPrice(plan.priceCop(), promo.discountType(), promo.discountValue());
        int saving = plan.priceCop() == 0 ? 0 :
                (int) Math.round((plan.priceCop() - price) * 100.0 / plan.priceCop());
        return new Offer(plan.code(), plan.title(), plan.description(), plan.priceCop(),
                price, promo == null ? null : promo.code(),
                promo == null ? null : promo.title(), saving, false);
    }

    @GetMapping("/billing/catalog")
    public Catalog catalog() {
        Instant now = Instant.now();
        List<Promotion> promoList = promotions();
        List<Offer> offers = new ArrayList<>();
        for (Plan plan : plans()) {
            if (!plan.active()) continue;
            Promotion best = null;
            long bestPrice = plan.priceCop();
            for (Promotion promo : promoList) {
                if (promo.published() && promo.planCode().equals(plan.code()) && validNow(promo, now)) {
                    try {
                        long candidate = BillingPricingRules.discountedPrice(plan.priceCop(), promo.discountType(), promo.discountValue());
                        if (candidate < bestPrice) { bestPrice = candidate; best = promo; }
                    } catch (IllegalArgumentException ignored) { /* Price changed; expired offer is not shown. */ }
                }
            }
            offers.add(makeOffer(plan, best));
        }
        return new Catalog("COP", false, offers);
    }

    @GetMapping("/billing/quote")
    public Offer quote(@RequestParam String planCode,
                       @RequestParam(required = false) String promoCode) {
        Plan selected = plan(planCode);
        if (!selected.active()) throw bad("Este plan no está disponible");
        if (promoCode == null || promoCode.isBlank()) {
            return catalog().offers().stream().filter(p -> p.code().equals(planCode))
                    .findFirst().orElseThrow(() -> bad("Plan no disponible"));
        }
        String normalized = promoCode.trim().toUpperCase(Locale.ROOT);
        Promotion promo = promotions().stream().filter(p -> p.code().equals(normalized)
                        && p.planCode().equals(planCode) && validNow(p, Instant.now()))
                .findFirst().orElseThrow(() -> bad("Código promocional inválido o vencido"));
        try { return makeOffer(selected, promo); }
        catch (IllegalArgumentException ex) { throw bad("La promoción no es compatible con el precio actual"); }
    }

    @GetMapping("/billing/me")
    public Map<String,Object> myEntitlement(Authentication auth) {
        User user = actor(auth);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT plan_code,source,valid_until,issued_at
            FROM billing_entitlements WHERE user_id = ? AND (valid_until IS NULL OR valid_until > now())
            """, user.getId());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("planCode", rows.isEmpty() ? "FREE" : rows.get(0).get("plan_code"));
        result.put("pro", !rows.isEmpty());
        if (!rows.isEmpty()) {
            result.put("validUntil", rows.get(0).get("valid_until"));
            result.put("source", rows.get(0).get("source"));
        }
        return result;
    }

    @GetMapping("/admin/billing/plans")
    public List<Plan> adminPlans() { return plans(); }

    @PutMapping("/admin/billing/plans/{code}")
    @Transactional
    public Plan updatePlan(@PathVariable String code, @RequestBody PlanUpdate input, Authentication auth) {
        User admin = actor(auth);
        Plan old = plan(code);
        if (input == null || input.priceCop() == null || input.active() == null
                || input.sortOrder() == null) throw bad("Completa los datos del plan");
        String title = safeText(input.title(), 2, 90, "Nombre");
        String description = safeText(input.description(), 0, 240, "Descripción");
        long price = input.priceCop();
        if (price < 0 || price > 10_000_000 || (!code.equals("FREE") && price < 1000))
            throw bad("Precio fuera de rango");
        if (code.equals("FREE") && (price != 0 || !input.active())) throw bad("FinFlow Free siempre estará disponible");
        if (input.sortOrder() < 0 || input.sortOrder() > 99) throw bad("Orden inválido");
        jdbc.update("""
            UPDATE billing_plans SET title=?,description=?,price_cop=?,is_active=?,
                sort_order=?,updated_at=now() WHERE code=?
            """, title,description,price,input.active(),input.sortOrder(),code);
        audit(admin, "PLAN_UPDATED", code, old.priceCop() + " -> " + price);
        return plan(code);
    }

    @GetMapping("/admin/billing/promotions")
    public List<Promotion> adminPromotions() { return promotions(); }

    @PostMapping("/admin/billing/promotions")
    @Transactional
    public Promotion createPromotion(@RequestBody PromotionInput input, Authentication auth) {
        User admin = actor(auth);
        PromotionInput checked = checkPromotion(input);
        if (promotions().stream().anyMatch(p -> p.code().equals(checked.code())))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ese código ya existe");
        jdbc.update("""
            INSERT INTO billing_promotions(code,title,plan_code,discount_type,discount_value,
                starts_at,ends_at,max_redemptions,is_active,is_public,created_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, checked.code(),checked.title(),checked.planCode(),checked.discountType(),
                checked.discountValue(),Timestamp.from(checked.startsAt()),Timestamp.from(checked.endsAt()),
                checked.maxRedemptions(),checked.active(),checked.published(),admin.getId());
        audit(admin,"PROMOTION_CREATED",checked.code(),"Plan: "+checked.planCode());
        return promotions().stream().filter(p -> p.code().equals(checked.code())).findFirst().orElseThrow();
    }

    @PutMapping("/admin/billing/promotions/{code}")
    @Transactional
    public Promotion updatePromotion(@PathVariable String code, @RequestBody PromotionInput input, Authentication auth) {
        User admin = actor(auth);
        PromotionInput checked = checkPromotion(input);
        if (!code.equals(checked.code()) || promotions().stream().noneMatch(p -> p.code().equals(code)))
            throw bad("El código promocional no se puede cambiar");
        jdbc.update("""
            UPDATE billing_promotions SET title=?,plan_code=?,discount_type=?,discount_value=?,
                starts_at=?,ends_at=?,max_redemptions=?,is_active=?,is_public=?,updated_at=now()
            WHERE code=?
            """, checked.title(),checked.planCode(),checked.discountType(),checked.discountValue(),
                Timestamp.from(checked.startsAt()),Timestamp.from(checked.endsAt()),
                checked.maxRedemptions(),checked.active(),checked.published(),code);
        audit(admin,"PROMOTION_UPDATED",code, "Actualizada sin alterar redenciones");
        return promotions().stream().filter(p -> p.code().equals(code)).findFirst().orElseThrow();
    }

    @PostMapping("/admin/billing/grants")
    @Transactional
    public Map<String,Object> grant(@RequestBody GrantInput input, Authentication auth) {
        User admin = actor(auth);
        if (input == null || input.userId() == null || input.userId() <= 0 ||
                input.planCode() == null || !Set.of("MONTHLY","YEARLY","LIFETIME").contains(input.planCode()))
            throw bad("Selecciona un usuario y un plan válido");
        String reason = safeText(input.reason(), 8, 240, "Motivo");
        if (!users.existsById(input.userId()))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Usuario no encontrado");
        List<Map<String,Object>> previous = jdbc.queryForList(
                "SELECT plan_code,valid_until FROM billing_entitlements WHERE user_id = ?",input.userId());
        if (!previous.isEmpty() && "LIFETIME".equals(previous.get(0).get("plan_code"))
                && !"LIFETIME".equals(input.planCode()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"No se puede reemplazar un acceso de por vida");
        Instant end = switch(input.planCode()) {
            case "MONTHLY" -> Instant.now().atZone(ZoneOffset.UTC).plusMonths(1).toInstant();
            case "YEARLY" -> Instant.now().atZone(ZoneOffset.UTC).plusYears(1).toInstant();
            default -> null;
        };
        jdbc.update("""
            INSERT INTO billing_entitlements(user_id,plan_code,source,valid_until,assigned_by,reason)
            VALUES (?,?,'ADMIN_GRANT',?,?,?)
            ON CONFLICT(user_id) DO UPDATE SET plan_code=excluded.plan_code,
              source=excluded.source,valid_until=excluded.valid_until,
              assigned_by=excluded.assigned_by,reason=excluded.reason,updated_at=now()
            """, input.userId(),input.planCode(),end == null ? null : Timestamp.from(end),admin.getId(),reason);
        audit(admin,"ACCESS_GRANTED",String.valueOf(input.userId()),
                input.planCode()+" · "+reason);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("status","granted");
        result.put("userId",input.userId());
        result.put("planCode",input.planCode());
        result.put("validUntil",end);
        return result;
    }

    private PromotionInput checkPromotion(PromotionInput input) {
        if (input == null) throw bad("Promoción requerida");
        String code = input.code() == null ? "" : input.code().trim().toUpperCase(Locale.ROOT);
        if (!PROMO_CODE.matcher(code).matches()) throw bad("Código de 4 a 32 caracteres (A-Z, 0-9, _ o -)");
        String title = safeText(input.title(),2,90,"Título");
        if (input.planCode() == null || !Set.of("MONTHLY","YEARLY","LIFETIME").contains(input.planCode()))
            throw bad("Selecciona un plan Pro");
        Plan plan = plan(input.planCode());
        if (input.discountType() == null || input.discountValue() == null) throw bad("Descuento requerido");
        try { BillingPricingRules.discountedPrice(plan.priceCop(),input.discountType(),input.discountValue()); }
        catch (IllegalArgumentException ex) { throw bad(ex.getMessage()); }
        if (input.startsAt() == null || input.endsAt() == null ||
                !input.endsAt().isAfter(input.startsAt()) ||
                input.endsAt().isAfter(Instant.now().plusSeconds(86400L * 366 * 5)))
            throw bad("Fechas inválidas (máximo cinco años)");
        if (input.maxRedemptions() != null && (input.maxRedemptions() < 1 || input.maxRedemptions() > 100000))
            throw bad("Límite de usos entre 1 y 100000");
        if (input.active() == null || input.published() == null) throw bad("Estado de promoción requerido");
        return new PromotionInput(code,title,input.planCode(),input.discountType(),input.discountValue(),
                input.startsAt(),input.endsAt(),input.maxRedemptions(),input.active(),input.published());
    }

    private String safeText(String value, int min, int max, String label) {
        String text = value == null ? "" : value.trim();
        if (text.length() < min || text.length() > max) throw bad(label + " debe tener entre "+min+" y "+max+" caracteres");
        return text;
    }
    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private User actor(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión requerida");
        return users.findByEmail(auth.getName()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión inválida"));
    }
    private void audit(User admin,String action,String subject,String details) {
        jdbc.update("INSERT INTO billing_admin_audit(actor_user_id,action,subject,details) VALUES (?,?,?,?)",
                admin.getId(),action,subject,details);
    }
}
