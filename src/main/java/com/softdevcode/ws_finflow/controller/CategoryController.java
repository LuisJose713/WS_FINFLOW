package com.softdevcode.ws_finflow.controller;

import com.softdevcode.ws_finflow.entity.Category;
import com.softdevcode.ws_finflow.repository.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryRepository repository;

    @GetMapping
    public List<Category> getAll() {
        return repository.findAll();
    }
}