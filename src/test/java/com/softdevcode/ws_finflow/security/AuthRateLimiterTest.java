package com.softdevcode.ws_finflow.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthRateLimiterTest {
  @Mock JdbcTemplate jdbc;
  @InjectMocks AuthRateLimiter limiter;

  @Test void normalizesCaseWithoutStoringPlainEmail() {
    limiter.recordFailure("Owner@example.com");
    limiter.recordFailure("owner@example.com");
    verify(jdbc,times(2)).update(anyString(),eq(
        "c8cd3c6427301eaf6665bccacd65ddb614527acc843a15463e3faba57124c351"));
  }

  @Test void clearingDoesNotPassRawEmailToDatabase() {
    limiter.clear("person@example.com");
    verify(jdbc).update(contains("DELETE FROM auth_rate_limits"),argThat(s->s.length()==64 && !s.contains("@")));
  }
}
