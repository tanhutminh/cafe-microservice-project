package com.cafe.menuservice.category;

import com.cafe.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    public List<Category> findAll() {
        return categoryRepository.findAllByOrderByDisplayOrderAsc();
    }

    public Category findById(Long id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Category", id));
    }

    @Transactional
    public Category create(String name, int displayOrder, boolean active) {
        Category category = Category.builder()
                .name(name)
                .displayOrder(displayOrder)
                .active(active)
                .build();
        return categoryRepository.save(category);
    }

    @Transactional
    public Category update(Long id, String name, int displayOrder, boolean active) {
        Category category = findById(id);
        category.setName(name);
        category.setDisplayOrder(displayOrder);
        category.setActive(active);
        return categoryRepository.save(category);
    }

    @Transactional
    public void delete(Long id) {
        Category category = findById(id);
        category.setActive(false);
        categoryRepository.save(category);
    }
}
