package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/auth/profile-email")
@RequiredArgsConstructor
public class ProfileEmailController {
    private static final Pattern EMAIL=Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final ObjectProvider<JavaMailSender> senders;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    @Value("${finflow.email.verification-enabled:false}") private boolean enabled;
    @Value("${finflow.email.challenge-secret:}") private String secret;
    @Value("${finflow.email.from:}") private String fromEmail;

    public record Request(String newEmail, String currentPassword) {}
    public record Confirm(String code) {}

    @PostMapping("/request")
    @Transactional
    public Map<String,Object> request(@RequestBody Request input, Authentication auth) {
        User user=actor(auth);
        JavaMailSender sender=mail();
        if(input==null||input.newEmail()==null||input.currentPassword()==null||
                !encoder.matches(input.currentPassword(),user.getPassword()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Verifica la contraseña actual");
        String email=input.newEmail().trim().toLowerCase(Locale.ROOT);
        if(email.length()>254||!EMAIL.matcher(email).matches()||
                email.equalsIgnoreCase(user.getEmail()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Ingresa otro correo válido");
        if(users.findByEmail(email).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"El correo no está disponible");
        Long recent=jdbc.queryForObject("""
             SELECT COUNT(*) FROM profile_email_request_history WHERE user_id=?
              AND requested_at > now() - INTERVAL '1 day'
             """,Long.class,user.getId());
        if(recent!=null&&recent>=3)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Límite de verificaciones alcanzado hoy");
        Long cooldown=jdbc.queryForObject("""
             SELECT COUNT(*) FROM profile_email_request_history WHERE user_id=?
              AND requested_at > now() - INTERVAL '2 minutes'
             """,Long.class,user.getId());
        if(cooldown!=null&&cooldown>0)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Espera dos minutos antes de volver a pedir un código");
        String code=String.format(Locale.ROOT,"%08d",random.nextInt(100_000_000));
        Instant expiry=Instant.now().plusSeconds(900);
        jdbc.update("""
            INSERT INTO profile_email_challenges(user_id,new_email,code_hmac,expires_at,requested_at,attempts)
            VALUES (?,?,?,?,now(),0)
            ON CONFLICT(user_id) DO UPDATE SET new_email=excluded.new_email,
               code_hmac=excluded.code_hmac,expires_at=excluded.expires_at,
               requested_at=now(),attempts=0
            """,user.getId(),email,mac(user.getId(),email,code),Timestamp.from(expiry));
        jdbc.update("INSERT INTO profile_email_request_history(user_id) VALUES (?)",user.getId());
        SimpleMailMessage message=new SimpleMailMessage();
        message.setFrom(fromEmail);
        message.setTo(email);
        message.setSubject("FinFlow: verifica tu nuevo correo");
        message.setText("Tu código de verificación es: "+code+
                "\nCaduca en 15 minutos. Si no solicitaste este cambio, ignora el mensaje.");
        try { sender.send(message); }
        catch(Exception ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"No se pudo enviar el correo de verificación"); }
        return Map.of("status","CODE_SENT","expiresInSeconds",900);
    }

    @PostMapping("/confirm")
    @Transactional
    public Map<String,Object> confirm(@RequestBody Confirm input, Authentication auth) {
        User user=actor(auth);
        if(input==null||input.code()==null||!input.code().matches("[0-9]{8}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Código inválido");
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT new_email,code_hmac,expires_at,attempts
            FROM profile_email_challenges WHERE user_id=? FOR UPDATE
            """,user.getId());
        if(rows.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Solicita un código antes de confirmar");
        Map<String,Object> row=rows.get(0);
        Timestamp expiry=(Timestamp)row.get("expires_at");
        if(expiry.toInstant().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El código caducó. Solicita otro");
        }
        int attempts=((Number)row.get("attempts")).intValue();
        if(attempts>=5)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Se agotaron los intentos");
        String email=String.valueOf(row.get("new_email"));
        boolean matches=MessageDigest.isEqual(
                mac(user.getId(),email,input.code()).getBytes(StandardCharsets.US_ASCII),
                String.valueOf(row.get("code_hmac")).getBytes(StandardCharsets.US_ASCII));
        if(!matches) {
            jdbc.update("UPDATE profile_email_challenges SET attempts=attempts+1 WHERE user_id=?",user.getId());
            // Do not roll back invalid attempt count: record and return status.
            return Map.of("status","INVALID_CODE","attemptsRemaining",Math.max(0,4-attempts));
        }
        if(users.findByEmail(email).isPresent())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"El correo ya no está disponible");
        user.setEmail(email);
        user.setTokenVersion((user.getTokenVersion()==null?0:user.getTokenVersion())+1);
        users.save(user);
        jdbc.update("DELETE FROM profile_email_challenges WHERE user_id=?",user.getId());
        return Map.of("status","VERIFIED","logoutRequired",true);
    }

    private JavaMailSender mail() {
        JavaMailSender sender=senders.getIfAvailable();
        if(!enabled||sender==null||secret==null||secret.length()<32||
                fromEmail==null||!EMAIL.matcher(fromEmail).matches())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "La verificación por correo aún no está configurada");
        return sender;
    }

    private String mac(Long userId,String email,String code) {
        try {
            Mac hmac=Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] digest=hmac.doFinal((userId+":"+email+":"+code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch(Exception ex) { throw new IllegalStateException("No se puede verificar el correo",ex); }
    }

    private User actor(Authentication auth){
        if(auth==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Inicia sesión");
        return users.findByEmail(auth.getName()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sesión inválida"));
    }
}
