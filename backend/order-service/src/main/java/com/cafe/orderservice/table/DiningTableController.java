package com.cafe.orderservice.table;

import com.cafe.orderservice.table.dto.DiningTableRequest;
import com.cafe.orderservice.table.dto.DiningTableResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

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
  public DiningTableResponse update(
      @Parameter(description = "The table's id", example = "3") @PathVariable @Positive Long id,
      @Valid @RequestBody DiningTableRequest request) {
    return DiningTableResponse.from(diningTableService.update(id, request));
  }

  @DeleteMapping("/{id}")
  @Operation(summary = "Soft-delete a table (ADMIN only)")
  public void delete(
      @Parameter(description = "The table's id", example = "3") @PathVariable @Positive Long id) {
    diningTableService.delete(id);
  }

  @PostMapping("/{id}/release")
  @Operation(
      summary = "Mark a table empty again",
      description =
          "A manual staff action, independent of order/payment status - since an "
              + "order can be PAID while the guest is still seated, the table doesn't "
              + "auto-release on payment. Blocked unless every order ever tied to the table is "
              + "CANCELLED or PAID.")
  public DiningTableResponse release(
      @Parameter(description = "The table's id", example = "3") @PathVariable @Positive Long id) {
    diningTableService.release(id);
    return DiningTableResponse.from(diningTableService.findById(id));
  }

  @PostMapping("/{id}/occupy")
  @Operation(
      summary = "Mark an AVAILABLE table occupied",
      description =
          "Called the moment staff pick an AVAILABLE table to start building an order on it, "
              + "before any Order row exists - items are picked in a local draft cart on the POS screen "
              + "and only sent to the server as a whole when the order is confirmed. Occupying up front "
              + "closes the window where two staff members could both start building an order on the "
              + "same table: the status check and the write are one atomic conditional UPDATE, so at "
              + "most one of two near-simultaneous requests can succeed.")
  public DiningTableResponse occupy(
      @Parameter(description = "The table's id", example = "3") @PathVariable @Positive Long id) {
    diningTableService.occupy(id);
    return DiningTableResponse.from(diningTableService.findById(id));
  }
}
