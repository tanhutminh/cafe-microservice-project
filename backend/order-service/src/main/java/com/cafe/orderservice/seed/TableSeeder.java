package com.cafe.orderservice.seed;

import com.cafe.orderservice.table.DiningTable;
import com.cafe.orderservice.table.DiningTableRepository;
import com.cafe.orderservice.table.TableStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Dev-only bootstrap: seeds a handful of dining tables so the POS screen has something to show. */
@Component
public class TableSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TableSeeder.class);
    private static final int TABLE_COUNT = 8;

    private final DiningTableRepository diningTableRepository;

    public TableSeeder(DiningTableRepository diningTableRepository) {
        this.diningTableRepository = diningTableRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (diningTableRepository.count() > 0) {
            return;
        }

        for (int i = 1; i <= TABLE_COUNT; i++) {
            diningTableRepository.save(DiningTable.builder()
                    .tableNumber("Bàn " + i)
                    .capacity(4)
                    .status(TableStatus.AVAILABLE)
                    .active(true)
                    .build());
        }
        log.info("Seeded {} dining tables (dev only).", TABLE_COUNT);
    }
}
