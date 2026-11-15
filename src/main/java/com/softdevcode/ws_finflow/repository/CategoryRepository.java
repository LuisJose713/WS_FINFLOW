package com.softdevcode.ws_finflow.repository;

import com.softdevcode.ws_finflow.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByType(String type);

    @Query("select c from Category c where c.userId is null or c.userId = :userId")
    List<Category> findVisibleUnsorted(@Param("userId") Integer userId);

    default List<Category> findVisibleTo(Integer userId) {
        return findVisibleUnsorted(userId).stream()
            .sorted(java.util.Comparator.comparing(Category::getName, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    Optional<Category> findByIdAndUserId(Long id, Integer userId);

    default boolean existsByUserIdAndNameIgnoreCaseAndTypeIgnoreCase(Integer userId, String name, String type) {
        return findVisibleTo(userId).stream().anyMatch(c -> java.util.Objects.equals(c.getUserId(), userId)
            && c.getName().equalsIgnoreCase(name) && c.getType().equalsIgnoreCase(type));
    }
}
