package com.softdevcode.ws_finflow.sync;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** Only the JWT-verified owner may read their own refresh hints. */
@RestController
@RequestMapping("/sync")
@RequiredArgsConstructor
public class FinanceSyncController {
    private final FinanceChangeLog log;
    private final UserRepository users;

    private Integer owner(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return Math.toIntExact(users.findByEmail(authentication.getName())
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    }
    @GetMapping("/cursor")
    public Map<String, Long> cursor(Authentication authentication) {
        return Map.of("cursor", log.cursor(owner(authentication)));
    }
    @GetMapping("/changes")
    public FinanceChangeLog.Batch changes(Authentication authentication,
            @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "100") int limit) {
        return log.changes(owner(authentication), after, limit);
    }
}
