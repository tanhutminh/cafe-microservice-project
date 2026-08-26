package com.cafe.menuservice.menuitem;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

  /**
   * Fetch-joins category so it's already loaded when this method returns - open-in-view is
   * disabled, and by default Spring Data JPA runs this call in its own transaction that closes on
   * return (unless a caller already has a wider one active), so a lazy m.category accessed
   * afterward would otherwise throw LazyInitializationException.
   */
  @Query(
      """
            SELECT m FROM MenuItem m JOIN FETCH m.category
            WHERE m.active = true
            AND (:categoryId IS NULL OR m.category.id = :categoryId)
            AND (:available IS NULL OR m.available = :available)
            ORDER BY m.name
            """)
  List<MenuItem> search(
      @Param("categoryId") Long categoryId, @Param("available") Boolean available);

  @Query("SELECT m FROM MenuItem m JOIN FETCH m.category WHERE m.id = :id")
  Optional<MenuItem> findByIdWithCategory(@Param("id") Long id);

  /** Batch equivalent of {@link #findByIdWithCategory(Long)} - one query covering every id. */
  @Query("SELECT m FROM MenuItem m JOIN FETCH m.category WHERE m.id IN :ids")
  List<MenuItem> findAllByIdWithCategory(@Param("ids") List<Long> ids);
}
