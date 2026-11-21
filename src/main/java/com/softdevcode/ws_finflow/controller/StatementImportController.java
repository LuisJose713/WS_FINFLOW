package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.service.StatementImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Statement import never uses a client-supplied user ID.
 * Upload only previews; persistence requires a separate explicit confirmation.
 */
@RestController
@RequestMapping("/imports/statements")
@RequiredArgsConstructor
public class StatementImportController {
    private final StatementImportService imports;
    private final UserRepository users;

    private Integer owner(Authentication auth) {
        if (auth == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return Math.toIntExact(users.findByEmail(auth.getName()).map(User::getId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    }

    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public StatementImportService.Preview preview(Authentication auth,
            @RequestParam("accountId") Long accountId,
            @RequestPart("file") MultipartFile file) {
        return imports.preview(owner(auth), accountId, file);
    }

    @PostMapping(path = "/confirm", consumes = MediaType.APPLICATION_JSON_VALUE)
    public StatementImportService.Result confirm(Authentication auth,
            @RequestBody StatementImportService.Confirmation confirmation) {
        return imports.confirm(owner(auth), confirmation);
    }
}
