package com.softdevcode.ws_finflow.repository;

import com.softdevcode.ws_finflow.entity.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByIdAndUserId(Long id, Integer userId);

    List<Transaction> findByUserIdOrderByDateDesc(Integer userId);

    List<Transaction> findByUserIdOrderByDateDesc(Integer userId, Pageable pageable);

    List<Transaction> findByUserIdAndCategoryIdOrderByDateDesc(Integer userId, Long categoryId);

    List<Transaction> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(Integer userId, LocalDateTime start, LocalDateTime end);

    List<Transaction> findByUserIdAndStatusOrderByDateDesc(Integer userId, String status);
}