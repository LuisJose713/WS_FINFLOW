package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.dto.CategoryRequest;
import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.entity.User;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import com.softdevcode.ws_finflow.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController {
    private final CategoryRepository repository;
    private final UserRepository users;

    private Integer owner(Authentication authentication) {
        return Math.toIntExact(users.findByEmail(authentication.getName()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED)));
    }

    @GetMapping
    public List<Category> getAll(Authentication auth) {
        return repository.findVisibleTo(owner(auth));
    }

    @PostMapping
    public ResponseEntity<Category> create(Authentication auth, @RequestBody CategoryRequest request) {
        int userId = owner(auth);
        String name = validName(request);
        String type = validType(request);
        if (repository.existsByUserIdAndNameIgnoreCaseAndTypeIgnoreCase(userId, name, type)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya tienes esa categoría");
        }
        Category category = new Category();
        category.setUserId(userId);
        category.setName(name);
        category.setType(type);
        return ResponseEntity.status(HttpStatus.CREATED).body(save(category));
    }

    @PutMapping("/{id}")
    public Category update(Authentication auth, @PathVariable Long id, @RequestBody CategoryRequest request) {
        int userId = owner(auth);
        Category category = owned(userId, id); // categorias globales y de otros usuarios: 404
        String name = validName(request);
        String type = validType(request);
        boolean changed = !category.getName().equalsIgnoreCase(name) || !category.getType().equalsIgnoreCase(type);
        if (changed && repository.existsByUserIdAndNameIgnoreCaseAndTypeIgnoreCase(userId, name, type)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya tienes esa categoría");
        }
        category.setName(name);
        category.setType(type);
        return save(category);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication auth, @PathVariable Long id) {
        Category category = owned(owner(auth), id);
        try {
            repository.deleteAndFlush(category);
            return ResponseEntity.noContent().build();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La categoría está en uso y no puede eliminarse", e);
        }
    }

    private Category save(Category category) {
        try {
            return repository.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una categoría con esos datos", e);
        }
    }

    private Category owned(int userId, Long categoryId) {
        return repository.findByIdAndUserId(categoryId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Categoría no encontrada"));
    }

    private String validName(CategoryRequest request) {
        String name = request == null || request.getName() == null ? "" : request.getName().trim();
        if (name.length() < 2 || name.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre de 2 a 80 caracteres");
        }
        return name;
    }

    private String validType(CategoryRequest request) {
        String type = request == null || request.getType() == null ? "" : request.getType().trim();
        if ("income".equalsIgnoreCase(type)) return "Income";
        if ("expense".equalsIgnoreCase(type)) return "Expense";
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de categoría inválido");
    }
}
