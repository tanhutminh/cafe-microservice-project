package com.cafe.orderservice.table;

import com.cafe.orderservice.table.dto.DiningTableRequest;
import com.cafe.orderservice.table.dto.DiningTableResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tables")
public class DiningTableController {

    private final DiningTableService diningTableService;

    public DiningTableController(DiningTableService diningTableService) {
        this.diningTableService = diningTableService;
    }

    @GetMapping
    public List<DiningTableResponse> findAll() {
        return diningTableService.findAll().stream().map(DiningTableResponse::from).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DiningTableResponse create(@Valid @RequestBody DiningTableRequest request) {
        return DiningTableResponse.from(diningTableService.create(request));
    }

    @PutMapping("/{id}")
    public DiningTableResponse update(@PathVariable Long id, @Valid @RequestBody DiningTableRequest request) {
        return DiningTableResponse.from(diningTableService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        diningTableService.delete(id);
    }
}
