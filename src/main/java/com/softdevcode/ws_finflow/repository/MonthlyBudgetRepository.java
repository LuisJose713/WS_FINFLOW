package com.softdevcode.ws_finflow.repository;

import com.softdevcode.ws_finflow.entity.MonthlyBudget;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MonthlyBudgetRepository extends JpaRepository<MonthlyBudget, Long> {
    List<MonthlyBudget> findByUserIdAndYearAndMonthOrderByIdAsc(Integer userId, Integer year, Integer month);
    Optional<MonthlyBudget> findByIdAndUserId(Long id, Integer userId);
    boolean existsByUserIdAndCategoryIdAndYearAndMonth(Integer userId, Long categoryId, Integer year, Integer month);
}