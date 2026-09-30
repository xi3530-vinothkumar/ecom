package com.ecom.inventory.web;

import com.ecom.inventory.application.StockAdminService;
import com.ecom.inventory.domain.SkuQuantity;
import com.ecom.inventory.domain.StockLevel;
import com.ecom.platform.core.CallContext;
import com.ecom.platform.core.EcomException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin stock management. The Gateway already requires ADMIN; re-checked here (defense in depth). */
@RestController
@RequestMapping("/admin/inventory")
public class AdminInventoryController {

    public record SetStockRequest(@NotNull @Min(0) @Max(1_000_000_000) Integer onHand) {}

    public record StockLevelResponse(
            String sku, String warehouseId, int onHand, int reserved, int available, long version, Instant updatedAt) {

        static StockLevelResponse of(StockLevel s) {
            return new StockLevelResponse(
                    s.sku(), s.warehouseId(), s.onHand(), s.reserved(), s.available(), s.version(), s.updatedAt());
        }
    }

    private final StockAdminService admin;

    public AdminInventoryController(StockAdminService admin) {
        this.admin = admin;
    }

    @PutMapping("/{sku}")
    public StockLevelResponse setOnHand(@PathVariable String sku, @Valid @RequestBody SetStockRequest body) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        return StockLevelResponse.of(admin.setOnHand(validSku(sku), body.onHand()));
    }

    @GetMapping("/{sku}")
    public StockLevelResponse get(@PathVariable String sku) {
        CallContext.requireRole(CallContext.ROLE_ADMIN);
        return StockLevelResponse.of(admin.get(validSku(sku)));
    }

    private static String validSku(String sku) {
        if (sku.isBlank() || sku.length() > SkuQuantity.MAX_SKU_LENGTH) {
            throw EcomException.validation("Invalid SKU");
        }
        return sku;
    }
}
