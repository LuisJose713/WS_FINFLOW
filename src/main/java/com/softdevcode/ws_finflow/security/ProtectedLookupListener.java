package com.softdevcode.ws_finflow.security;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.entity.Category;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.springframework.stereotype.Component;
import java.util.Locale;

@Component
public class ProtectedLookupListener {
    private final FieldCipher cipher;
    public ProtectedLookupListener(FieldCipher cipher) { this.cipher = cipher; }
    @PrePersist @PreUpdate
    public void index(Object entity) {
        if (entity instanceof User user) {
            user.setEmailLookup(cipher.lookup("users.email", user.getEmail()));
            user.setUsernameLookup(cipher.lookup("users.username", user.getUsername()));
        } else if (entity instanceof Category category) {
            category.setNameLookup(cipher.lookup("categories.name", category.getName().toLowerCase(Locale.ROOT)));
        }
    }
}
