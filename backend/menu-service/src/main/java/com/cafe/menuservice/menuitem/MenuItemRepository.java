package com.cafe.menuservice.menuitem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    /**
     * Fetch-joins category so MenuItemResponse.from() can read category name/id
     * after this method returns — open-in-view is disabled, so a lazy
     * m.category accessed outside the @Transactional service method would
     * otherwise throw LazyInitializationException.
     */
    @Query("""
            SELECT m FROM MenuItem m JOIN FETCH m.category
            WHERE m.active = true
            AND (:categoryId IS NULL OR m.category.id = :categoryId)
            AND (:available IS NULL OR m.available = :available)
            ORDER BY m.name
            """)
    List<MenuItem> search(@Param("categoryId") Long categoryId, @Param("available") Boolean available);

    @Query("SELECT m FROM MenuItem m JOIN FETCH m.category WHERE m.id = :id")
    Optional<MenuItem> findByIdWithCategory(@Param("id") Long id);
}
