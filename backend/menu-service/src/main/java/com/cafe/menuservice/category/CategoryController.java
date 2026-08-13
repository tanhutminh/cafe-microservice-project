package com.cafe.menuservice.category;

import com.cafe.common.error.ApiError;
import com.cafe.menuservice.category.dto.CategoryRequest;
import com.cafe.menuservice.category.dto.CategoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
@Tag(name = "Categories", description = "Menu categories, e.g. \"Coffee\", \"Pastries\"")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    @Operation(summary = "List all active categories, ordered by displayOrder")
    public List<CategoryResponse> findAll() {
        return categoryService.findAll().stream().map(CategoryResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a category (ADMIN only)")
    public CategoryResponse create(@Valid @RequestBody CategoryRequest request) {
        Category category = categoryService.create(request.name(), request.displayOrder(), request.active());
        return CategoryResponse.from(category);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a category (ADMIN only)")
    @ApiResponse(responseCode = "404", description = "No category with this id",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class)))
    public CategoryResponse update(@Parameter(description = "The category's id", example = "1") @PathVariable @Positive Long id, @Valid @RequestBody CategoryRequest request) {
        Category category = categoryService.update(id, request.name(), request.displayOrder(), request.active());
        return CategoryResponse.from(category);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Soft-delete a category (ADMIN only) - sets active=false, does not remove the row")
    public void delete(@Parameter(description = "The category's id", example = "1") @PathVariable @Positive Long id) {
        categoryService.delete(id);
    }
}
