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
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Student Beta: opt-in application, manual review, time-limited eligibility.
 * Discount quotes are computed on the server and are NEVER proof of payment.
 * No student ID / documentation files are collected.
 */
@RestController
@RequiredArgsConstructor
public class StudentBetaController {
    private final JdbcTemplate jdbc;
    private final UserRepository users;

    public record Settings(boolean active, int discountPercent) {}
    public record SettingsUpdate(Boolean active, Integer discountPercent) {}
    public record ApplyRequest(String institution) {}
    public record ReviewRequest(String status) {}

    private User actor(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Inicia sesión");
        return users.findByEmail(auth.getName()).orElseThrow(() ->
          new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión inválida"));
    }

    private Settings settings() {
        return jdbc.queryForObject(
          "SELECT active, discount_percent FROM billing_student_beta_settings WHERE id=1",
          (rs,n) -> new Settings(rs.getBoolean("active"),rs.getInt("discount_percent")));
    }

    @GetMapping("/billing/student-beta")
    public Map<String,Object> myStatus(Authentication auth) {
        User user = actor(auth);
        Settings config = settings();
        List<Map<String,Object>> apps=jdbc.queryForList("""
            SELECT institution,status,submitted_at,approved_until FROM billing_student_beta_applications WHERE user_id=?
            """,user.getId());
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("enabled",config.active());
        out.put("discountPercent",config.discountPercent());
        out.put("availablePlans",List.of("MONTHLY","YEARLY"));
        out.put("hasApplied",!apps.isEmpty());
        if(!apps.isEmpty()){
            Map<String,Object> app=apps.get(0);
            boolean approved="APPROVED".equals(app.get("status"))
                   && app.get("approved_until") instanceof Timestamp expiry
                   && expiry.toInstant().isAfter(Instant.now());
            out.put("status",approved ? "APPROVED" :
                "APPROVED".equals(app.get("status")) ? "EXPIRED" : app.get("status"));
            out.put("institution",app.get("institution"));
            out.put("approvedUntil",app.get("approved_until"));
        } else {
            out.put("status","NOT_APPLIED");
        }
        return out;
    }

    @PostMapping("/billing/student-beta/apply")
    @Transactional
    public Map<String,Object> apply(@RequestBody ApplyRequest request, Authentication auth) {
        User user=actor(auth);
        if(!settings().active())throw new ResponseStatusException(HttpStatus.CONFLICT,"Convocatoria cerrada");
        String institution=request==null||request.institution()==null?"":request.institution().trim();
        if(institution.length()<3||institution.length()>120)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Indica tu institución educativa (3 a 120 caracteres)");
        int inserted=jdbc.update("""
            INSERT INTO billing_student_beta_applications(user_id,institution,status)
            VALUES (?,?,'PENDING') ON CONFLICT(user_id) DO NOTHING
            """,user.getId(),institution);
        if(inserted==0)throw new ResponseStatusException(HttpStatus.CONFLICT,
              "Ya tienes una solicitud. Contacta soporte para una revisión.");
        return Map.of("status","PENDING","message",
               "Solicitud recibida. El equipo verificará la condición de estudiante antes de aprobar el descuento.");
    }

    @GetMapping("/billing/student-beta/quote")
    public Map<String,Object> quote(@RequestParam String planCode, Authentication auth) {
        User user=actor(auth);
        if(!Set.of("MONTHLY","YEARLY").contains(planCode))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El descuento Beta aplica solo a mensual o anual");
        Settings conf=settings();
        if(!conf.active())throw new ResponseStatusException(HttpStatus.CONFLICT,"Oferta inactiva");
        Integer eligible=jdbc.queryForObject("""
            SELECT COUNT(*) FROM billing_student_beta_applications
            WHERE user_id=? AND status='APPROVED' AND approved_until>now()
            """,Integer.class,user.getId());
        if(eligible==null||eligible==0)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Tu condición de estudiante aún no está aprobada");
        Long price=jdbc.queryForObject("""
            SELECT price_cop FROM billing_plans WHERE code=? AND is_active=true
            """,Long.class,planCode);
        if(price==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Plan inactivo");
        long discounted=BillingPricingRules.discountedPrice(price,"PERCENT",conf.discountPercent());
        return Map.of("planCode",planCode,"originalCop",price,"priceCop",discounted,
            "discountPercent",conf.discountPercent(),"checkoutEnabled",false,
            "combinableWithOtherPromotions",false);
    }

    @GetMapping("/admin/billing/students")
    public List<Map<String,Object>> applications() {
        return jdbc.queryForList("""
           SELECT a.user_id,a.institution,a.status,a.submitted_at,a.reviewed_at,
                  a.approved_until
           FROM billing_student_beta_applications a
           ORDER BY a.submitted_at DESC LIMIT 250
           """);
    }

    @GetMapping("/admin/billing/student-settings")
    public Settings adminSettings() { return settings(); }

    @PutMapping("/admin/billing/student-settings")
    @Transactional
    public Settings updateSettings(@RequestBody SettingsUpdate input, Authentication auth) {
        User admin=actor(auth);
        if(input==null||input.active()==null||input.discountPercent()==null||
                input.discountPercent()<1||input.discountPercent()>70)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Descuento entre 1 y 70%");
        jdbc.update("""
            UPDATE billing_student_beta_settings
            SET active=?,discount_percent=?,updated_by=?,updated_at=now() WHERE id=1
            """,input.active(),input.discountPercent(),admin.getId());
        audit(admin,"STUDENT_BETA_UPDATED","program","Discount "+input.discountPercent()+"%; active="+input.active());
        return settings();
    }

    @PutMapping("/admin/billing/students/{userId}")
    @Transactional
    public Map<String,Object> review(@PathVariable long userId, @RequestBody ReviewRequest input, Authentication auth) {
        User admin=actor(auth);
        if(input==null||!Set.of("APPROVED","REJECTED").contains(input.status()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Decisión inválida");
        // 12-month validity requires a new verification after expiry.
        Timestamp until="APPROVED".equals(input.status()) ?
                Timestamp.from(Instant.now().plus(365,ChronoUnit.DAYS)):null;
        int count=jdbc.update("""
            UPDATE billing_student_beta_applications SET
              status=?,reviewed_at=now(),approved_until=?,reviewed_by=? WHERE user_id=?
            """,input.status(),until,admin.getId(),userId);
        if(count==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Solicitud inexistente");
        audit(admin,"STUDENT_BETA_REVIEW",String.valueOf(userId),input.status());
        return Map.of("userId",userId,"status",input.status());
    }

    private void audit(User admin,String action,String subject,String details) {
        jdbc.update("INSERT INTO billing_admin_audit(actor_user_id,action,subject,details) VALUES (?,?,?,?)",
                admin.getId(),action,subject,details);
    }
}
