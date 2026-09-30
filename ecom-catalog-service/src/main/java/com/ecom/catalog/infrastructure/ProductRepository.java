package com.ecom.catalog.infrastructure;

import com.ecom.catalog.domain.Product;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    /** Serializes writers of one product so version bumps and emitted events are strictly ordered. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findForUpdate(@Param("id") UUID id);

    @Query("select p from Product p where p.active = true and p.deletedAt is null")
    Page<Product> findVisible(Pageable pageable);

    @Query("select p from Product p where p.active = true and p.deletedAt is null and p.categoryId = :categoryId")
    Page<Product> findVisibleInCategory(@Param("categoryId") UUID categoryId, Pageable pageable);
}
