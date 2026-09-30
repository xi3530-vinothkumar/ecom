package com.ecom.catalog.infrastructure;

import com.ecom.catalog.domain.ProductVariant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductVariantRepository extends JpaRepository<ProductVariant, String> {

    @Query("select v from ProductVariant v join fetch v.product where v.sku = :sku")
    Optional<ProductVariant> findWithProduct(@Param("sku") String sku);

    @Query("select v from ProductVariant v join fetch v.product where v.sku in :skus")
    List<ProductVariant> findAllWithProduct(@Param("skus") Collection<String> skus);
}
