package com.softdevcode.ws_finflow.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RecurringScheduler {
    private static final Logger log=LoggerFactory.getLogger(RecurringScheduler.class);
    private final JdbcTemplate jdbc;
    private final RecurringRuleService service;

    // Runs while the Render worker is awake. A sleeping free instance catches up on wake.
    @Scheduled(fixedDelayString="${finflow.recurring.poll-ms:600000}",
               initialDelayString="${finflow.recurring.initial-delay-ms:60000}")
    public void checkDue() {
        List<Integer> owners=jdbc.query("""
            SELECT DISTINCT user_id FROM recurring_rules WHERE active=true AND next_due_date<=?
            ORDER BY user_id LIMIT 200
            """,(rs,i)->rs.getInt(1),Date.valueOf(LocalDate.now(ZoneId.of("America/Bogota"))));
        for(Integer owner:owners) {
            try { service.processDue(owner); }
            catch(Exception ex) { log.error("Recurring rule batch failed; will retry later"); }
        }
    }
}
