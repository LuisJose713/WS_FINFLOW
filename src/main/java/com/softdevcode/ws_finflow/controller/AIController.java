package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.AIAnalysisResponse;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.service.AIService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AIController {

    private final AIService       aiService;
    private final UserRepository  userRepository;

    // Helper — igual que en TransactionController
    private Integer getUserId(Authentication auth) {
        return Math.toIntExact(userRepository.findByEmail(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado")));
    }

    // GET /ai/analisis — análisis financiero completo
    @GetMapping("/analisis")
    public ResponseEntity<AIAnalysisResponse> getAnalisis(Authentication auth) {
        return ResponseEntity.ok(aiService.getAnalisis(getUserId(auth)));
    }

    // POST /ai/meta — proyección para una meta de ahorro
    @PostMapping("/meta")
    public ResponseEntity<Map<?, ?>> getMeta(
            Authentication auth,
            @RequestParam String descripcion,
            @RequestParam Double monto) {
        return ResponseEntity.ok(aiService.getMetaAhorro(getUserId(auth), descripcion, monto));
    }

    // POST /ai/chat — chat financiero
    // Cambia el tipo del body
    @PostMapping("/chat")
    public ResponseEntity<?> chat(
            Authentication auth,
            @RequestBody Map<String, Object> body) {  // ← Object, no String
        String mensaje = (String) body.get("mensaje");
        if (mensaje == null || mensaje.isBlank())
            return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(aiService.chat(getUserId(auth), mensaje));
    }
    // GET /ai/health — verifica que FastAPI esté up
    @GetMapping("/health")
    public ResponseEntity<Map<?, ?>> health() {
        return ResponseEntity.ok(aiService.health());
    }
}