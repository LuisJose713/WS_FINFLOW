package com.softdevcode.ws_finflow.config;

import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.UserRepository;
import com.softdevcode.ws_finflow.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests: authorization belongs on the API, not only Angular's UI.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminAuthorizationTest {
    @Autowired MockMvc mvc;
    @Autowired JwtUtil jwtUtil;
    @MockBean UserRepository users;

    private String bearer(String role) {
        User user = new User();
        user.setId(123L);
        user.setEmail("authz-test@finflow.invalid");
        user.setRole(role);
        user.setTokenVersion(0);
        when(users.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        return "Bearer " + jwtUtil.generateToken(user.getEmail(), false, 0);
    }

    @Test
    void normalUserCannotReadAdminOverview() throws Exception {
        mvc.perform(get("/admin/overview")
            .header("Authorization", bearer("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void normalUserCannotReadServiceMonitor() throws Exception {
        mvc.perform(get("/admin/monitor")
            .header("Authorization", bearer("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void normalUserCannotReadAdminBilling() throws Exception {
        mvc.perform(get("/admin/billing/plans")
            .header("Authorization", bearer("USER")))
            .andExpect(status().isForbidden());
    }

    @Test
    void adminCanAccessVerifyEndpoint() throws Exception {
        mvc.perform(get("/admin/verify")
            .header("Authorization", bearer("ADMIN")))
            .andExpect(status().isOk());
    }
}
