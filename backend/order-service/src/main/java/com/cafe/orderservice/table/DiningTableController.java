package com.cafe.orderservice.table;

import com.cafe.orderservice.table.dto.DiningTableRequest;
import com.cafe.orderservice.table.dto.DiningTableResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tables")
@Tag(name = "Tables", description = "Dining tables shown on the POS floor plan")
public class DiningTableController {

    private final DiningTableService diningTableService;

    public DiningTableController(DiningTableService diningTableService) {
        this.diningTableService = diningTableService;
    }

    @GetMapping
    @Operation(summary = "List all active tables")
    public List<DiningTableResponse> findAll() {
        return diningTableService.findAll().stream().map(DiningTableResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a table (ADMIN only)")
    public DiningTableResponse create(@Valid @RequestBody DiningTableRequest request) {
        return DiningTableResponse.from(diningTableService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a table's details (ADMIN only)")
    public DiningTableResponse update(@Parameter(description = "The table's id", example = "3") @PathVariable @Positive Long id, @Valid @RequestBody DiningTableRequest request) {
        return DiningTableResponse.from(diningTableService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a table (ADMIN only)")
    public void delete(@Parameter(description = "The table's id", example = "3") @PathVariable @Positive Long id) {
        diningTableService.delete(id);
    }

    @PostMapping("/{id}/release")
    @Operation(
            summary = "Mark a table empty again",
            description = "A manual staff action, independent of order/payment status - since an "
                    + "order can be PAID while the guest is still seated, the table doesn't "
                    + "auto-release on payment. Blocked if the table still has an active (non-CANCELLED) order."
    )
    public DiningTableResponse release(@Parameter(description = "The table's id", example = "3") @PathVariable @Positive Long id) {
        diningTableService.release(id);
        return DiningTableResponse.from(diningTableService.findById(id));
    }
}
