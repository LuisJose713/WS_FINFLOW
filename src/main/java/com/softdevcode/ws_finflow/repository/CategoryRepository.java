package com.softdevcode.ws_finflow.repository;

import com.softdevcode.ws_finflow.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByType(String type);

    @Query("select c from Category c where c.userId is null or c.userId = :userId order by c.name asc")
    List<Category> findVisibleTo(@Param("userId") Integer userId);

    Optional<Category> findByIdAndUserId(Long id, Integer userId);

    boolean existsByUserIdAndNameIgnoreCaseAndTypeIgnoreCase(Integer userId, String name, String type);
}