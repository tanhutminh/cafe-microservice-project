package com.cafe.menuservice.seed;

import com.cafe.menuservice.category.Category;
import com.cafe.menuservice.category.CategoryRepository;
import com.cafe.menuservice.category.CategoryService;
import com.cafe.menuservice.menuitem.MenuItemService;
import com.cafe.menuservice.menuitem.dto.MenuItemRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Dev-only bootstrap: seeds a full sample cafe menu if the categories table is
 * empty, so the app has something realistic to look at instead of an empty
 * admin screen. Mirrors auth-service's AdminSeeder pattern. A real deployment
 * would enter its own menu through the admin UI instead.
 */
@Component
public class MenuSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MenuSeeder.class);

    private record SeedItem(String name, String description, long price) {
    }

    private record SeedCategory(String name, List<SeedItem> items) {
    }

    private static final List<SeedCategory> MENU = List.of(
            new SeedCategory("Cà phê", List.of(
                    new SeedItem("Cà phê đen đá", "Cà phê phin truyền thống, đá", 25000),
                    new SeedItem("Cà phê đen nóng", "Cà phê phin truyền thống, nóng", 25000),
                    new SeedItem("Cà phê sữa đá", "Cà phê phin pha sữa đặc, đá", 29000),
                    new SeedItem("Cà phê sữa nóng", "Cà phê phin pha sữa đặc, nóng", 29000),
                    new SeedItem("Bạc xỉu", "Nhiều sữa, ít cà phê", 32000),
                    new SeedItem("Espresso", "Ý, đậm vị", 35000),
                    new SeedItem("Cappuccino", "Espresso, sữa đánh bọt", 45000),
                    new SeedItem("Latte", "Espresso, nhiều sữa nóng", 45000)
            )),
            new SeedCategory("Trà & Trà sữa", List.of(
                    new SeedItem("Trà đào cam sả", "Trà đào, cam, sả tươi", 39000),
                    new SeedItem("Trà vải", "Trà xanh, vải thiều", 39000),
                    new SeedItem("Hồng trà", "Trà đen truyền thống", 29000),
                    new SeedItem("Trà sữa trân châu đường đen", "Trà sữa, trân châu đường đen", 42000),
                    new SeedItem("Trà sữa matcha", "Trà sữa vị trà xanh Nhật", 45000)
            )),
            new SeedCategory("Nước ép & Sinh tố", List.of(
                    new SeedItem("Nước cam ép", "Cam tươi vắt nguyên chất", 35000),
                    new SeedItem("Nước ép dưa hấu", "Dưa hấu tươi ép", 32000),
                    new SeedItem("Sinh tố bơ", "Bơ sáp, sữa đặc", 45000),
                    new SeedItem("Sinh tố xoài", "Xoài chín, sữa chua", 42000)
            )),
            new SeedCategory("Đá xay", List.of(
                    new SeedItem("Đá xay matcha", "Trà xanh Nhật xay đá", 49000),
                    new SeedItem("Đá xay socola", "Socola nguyên chất xay đá", 49000),
                    new SeedItem("Đá xay caramel", "Caramel béo ngậy xay đá", 49000)
            )),
            new SeedCategory("Bánh ngọt", List.of(
                    new SeedItem("Bánh croissant", "Bánh sừng bò bơ Pháp", 35000),
                    new SeedItem("Bánh tiramisu", "Bánh Ý vị cà phê", 45000),
                    new SeedItem("Bánh cheesecake", "Phô mai kem New York", 49000),
                    new SeedItem("Bánh su kem", "Vỏ giòn, kem tươi", 25000)
            )),
            new SeedCategory("Đồ ăn nhẹ", List.of(
                    new SeedItem("Sandwich gà", "Bánh mì sandwich, gà nướng", 45000),
                    new SeedItem("Khoai tây chiên", "Khoai tây chiên giòn", 35000),
                    new SeedItem("Bánh mì que pate", "Bánh mì que, pate, chả lụa", 29000)
            ))
    );

    private final CategoryRepository categoryRepository;
    private final CategoryService categoryService;
    private final MenuItemService menuItemService;

    public MenuSeeder(CategoryRepository categoryRepository, CategoryService categoryService,
                       MenuItemService menuItemService) {
        this.categoryRepository = categoryRepository;
        this.categoryService = categoryService;
        this.menuItemService = menuItemService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (categoryRepository.count() > 0) {
            return;
        }

        int displayOrder = 1;
        int itemCount = 0;
        for (SeedCategory seedCategory : MENU) {
            Category category = categoryService.create(seedCategory.name(), displayOrder++, true);
            for (SeedItem item : seedCategory.items()) {
                menuItemService.create(new MenuItemRequest(
                        category.getId(), item.name(), item.description(),
                        BigDecimal.valueOf(item.price()), null, true, true));
                itemCount++;
            }
        }
        log.info("Seeded sample menu (dev only): {} categories, {} items.", MENU.size(), itemCount);
    }
}
