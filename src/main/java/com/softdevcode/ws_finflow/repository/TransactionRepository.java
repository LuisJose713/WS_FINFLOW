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

    List<Transaction> findAllByUserId(Integer userId);

    default List<Transaction> findByUserIdOrderByDateDesc(Integer userId) {
        return findAllByUserId(userId).stream().sorted(java.util.Comparator.comparing(Transaction::getDate,
            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())).reversed()
            .thenComparing(Transaction::getId,java.util.Comparator.reverseOrder())).toList();
    }

    default List<Transaction> findByUserIdOrderByDateDesc(Integer userId, Pageable pageable) {
        return findByUserIdOrderByDateDesc(userId).stream().skip(pageable.getOffset()).limit(pageable.getPageSize()).toList();
    }

    default List<Transaction> findByUserIdAndCategoryIdOrderByDateDesc(Integer userId, Long categoryId) {
        return findByUserIdOrderByDateDesc(userId).stream().filter(t->java.util.Objects.equals(t.getCategoryId(),categoryId)).toList();
    }

    List<Transaction> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(Integer userId, LocalDateTime start, LocalDateTime end);

    default List<Transaction> findByUserIdAndStatusOrderByDateDesc(Integer userId, String status) {
        return findByUserIdOrderByDateDesc(userId).stream().filter(t->java.util.Objects.equals(t.getStatus(),status)).toList();
    }
}
