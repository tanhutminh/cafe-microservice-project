package com.cafe.inventoryservice.recipe;

import com.cafe.inventoryservice.config.SecurityConfig;
import com.cafe.inventoryservice.ingredient.Ingredient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** @WebMvcTest slice test - see order-service's OrderControllerTest for the pattern this mirrors. */
@WebMvcTest(controllers = RecipeController.class)
@Import(SecurityConfig.class)
class RecipeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RecipeService recipeService;

    private MenuItemIngredient sampleRecipeLine() {
        Ingredient ingredient = Ingredient.builder()
                .id(7L)
                .name("Milk")
                .unit("liter")
                .currentStock(BigDecimal.TEN)
                .minStock(BigDecimal.valueOf(2))
                .reservedQuantity(BigDecimal.ZERO)
                .active(true)
                .build();
        return MenuItemIngredient.builder()
                .id(1L)
                .menuItemId(12L)
                .ingredient(ingredient)
                .quantityRequired(BigDecimal.valueOf(0.2))
                .build();
    }

    private static Stream<Arguments> validationFailures() {
        return Stream.of(
                Arguments.of("findByMenuItemId_negativeId",
                        (Supplier<MockHttpServletRequestBuilder>) () -> get("/api/menu-items/-1/recipe"),
                        "menuItemId", "must be greater than 0"),
                Arguments.of("replace_negativeMenuItemId",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/menu-items/-1/recipe")
                                .contentType(MediaType.APPLICATION_JSON).content("[]"),
                        "menuItemId", "must be greater than 0"),
                Arguments.of("replace_missingIngredientIdOnLine",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/menu-items/12/recipe")
                                .contentType(MediaType.APPLICATION_JSON).content("[{\"quantityRequired\":0.5}]"),
                        "ingredientId", "must not be null"),
                Arguments.of("replace_negativeIngredientIdOnLine",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/menu-items/12/recipe")
                                .contentType(MediaType.APPLICATION_JSON).content("[{\"ingredientId\":-1,\"quantityRequired\":0.5}]"),
                        "ingredientId", "must be greater than 0"),
                Arguments.of("replace_missingQuantityRequiredOnLine",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/menu-items/12/recipe")
                                .contentType(MediaType.APPLICATION_JSON).content("[{\"ingredientId\":7}]"),
                        "quantityRequired", "must not be null"),
                Arguments.of("replace_quantityRequiredBelowMinimum",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/menu-items/12/recipe")
                                .contentType(MediaType.APPLICATION_JSON).content("[{\"ingredientId\":7,\"quantityRequired\":0}]"),
                        "quantityRequired", "must be greater than or equal to 0.001")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validationFailures")
    void invalidRequest_returns400WithFieldViolation(
            String caseName, Supplier<MockHttpServletRequestBuilder> requestSupplier, String expectedField, String expectedMessage) throws Exception {
        mockMvc.perform(requestSupplier.get()
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].field").value(expectedField))
                .andExpect(jsonPath("$.validationErrors[0].message").value(expectedMessage));
    }

    @Test
    void replace_tooManyLines_returns400WithFieldViolation() throws Exception {
        StringBuilder body = new StringBuilder("[");
        for (int i = 1; i <= 51; i++) {
            if (i > 1) body.append(",");
            body.append("{\"ingredientId\":").append(i).append(",\"quantityRequired\":0.5}");
        }
        body.append("]");

        mockMvc.perform(put("/api/menu-items/12/recipe")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].field").value("lines"))
                .andExpect(jsonPath("$.validationErrors[0].message").value("size must be between 0 and 50"));
    }

    private static Stream<Arguments> happyPathRequests() {
        return Stream.of(
                Arguments.of("findByMenuItemId", (Supplier<MockHttpServletRequestBuilder>) () -> get("/api/menu-items/12/recipe")),
                Arguments.of("replace", (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/menu-items/12/recipe")
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"ingredientId\":7,\"quantityRequired\":0.2}]"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("happyPathRequests")
    void validRequest_returns200WithRecipeLines(String caseName, Supplier<MockHttpServletRequestBuilder> requestSupplier) throws Exception {
        List<MenuItemIngredient> lines = List.of(sampleRecipeLine());
        if ("findByMenuItemId".equals(caseName)) {
            when(recipeService.findByMenuItemId(12L)).thenReturn(lines);
        } else {
            when(recipeService.replace(eq(12L), any())).thenReturn(lines);
        }

        MvcResult result = mockMvc.perform(requestSupplier.get()
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode item = objectMapper.readTree(result.getResponse().getContentAsString()).get(0);
        assertAll(
                () -> assertThat(item.get("ingredientId").asLong()).isEqualTo(7L),
                () -> assertThat(item.get("ingredientName").asText()).isEqualTo("Milk"),
                () -> assertThat(item.get("unit").asText()).isEqualTo("liter"),
                () -> assertThat(item.get("quantityRequired").asDouble()).isEqualTo(0.2)
        );
    }
}
