package com.softdevcode.ws_finflow.repository;

import com.softdevcode.ws_finflow.entity.PlannedExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface PlannedExpenseRepository extends JpaRepository<PlannedExpense, Long> {

    List<PlannedExpense> findAllByUserId(Integer userId);
    default List<PlannedExpense> findByUserIdOrderByDueDateAsc(Integer userId) {
        return findAllByUserId(userId).stream().sorted(java.util.Comparator.comparing(PlannedExpense::getDueDate,
            java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
    }

    Optional<PlannedExpense> findByIdAndUserId(Long id, Integer userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pe from PlannedExpense pe where pe.id = :id and pe.userId = :userId")
    Optional<PlannedExpense> lockByIdAndUserId(@Param("id") Long id, @Param("userId") Integer userId);

    // Gastos que vencen hoy o antes (para alertas)
    default List<PlannedExpense> findByUserIdAndStatusAndDueDateLessThanEqual(
            Integer userId, String status, LocalDate date) {
        return findByUserIdOrderByDueDateAsc(userId).stream().filter(p->java.util.Objects.equals(p.getStatus(),status)
            && p.getDueDate()!=null && !p.getDueDate().isAfter(date)).toList();
    }

    // Gastos a recordar hoy
    default List<PlannedExpense> findByUserIdAndRemindAt(Integer userId, LocalDate remindAt) {
        return findByUserIdOrderByDueDateAsc(userId).stream().filter(p->java.util.Objects.equals(p.getRemindAt(),remindAt)).toList();
    }
}
