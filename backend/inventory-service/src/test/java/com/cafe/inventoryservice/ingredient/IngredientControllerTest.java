package com.cafe.inventoryservice.ingredient;

import com.cafe.inventoryservice.config.SecurityConfig;
import com.cafe.inventoryservice.reservation.StockMovement;
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
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** @WebMvcTest slice test - see order-service's OrderControllerTest for the pattern this mirrors. */
@WebMvcTest(controllers = IngredientController.class)
@Import(SecurityConfig.class)
class IngredientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private IngredientService ingredientService;

    private Ingredient sampleIngredient() {
        return Ingredient.builder()
                .id(7L)
                .name("Milk")
                .unit("liter")
                .currentStock(BigDecimal.TEN)
                .minStock(BigDecimal.valueOf(2))
                .reservedQuantity(BigDecimal.ZERO)
                .active(true)
                .build();
    }

    private static Stream<Arguments> updateValidationFailures() {
        return Stream.of(
                Arguments.of("negativeId", "/api/ingredients/-1",
                        "{\"name\":\"Milk\",\"unit\":\"liter\",\"minStock\":2,\"active\":true}", "id"),
                Arguments.of("unitTooLong", "/api/ingredients/7",
                        "{\"name\":\"Milk\",\"unit\":\"this-unit-name-is-way-too-long-to-be-real\",\"minStock\":2,\"active\":true}", "unit")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateValidationFailures")
    void update_invalidRequest_returns400WithFieldViolation(String caseName, String url, String body, String expectedField) throws Exception {
        mockMvc.perform(put(url)
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].field").value(expectedField));
    }

    private static Stream<Arguments> negativeIdRequests() {
        return Stream.of(
                Arguments.of("stockIn", (Supplier<MockHttpServletRequestBuilder>) () ->
                        post("/api/ingredients/-1/stock-in").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5.5}")),
                Arguments.of("movements", (Supplier<MockHttpServletRequestBuilder>) () ->
                        get("/api/ingredients/-1/movements")),
                Arguments.of("delete", (Supplier<MockHttpServletRequestBuilder>) () ->
                        delete("/api/ingredients/-1"))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("negativeIdRequests")
    void negativeId_returns400BeforeReachingService(String caseName, Supplier<MockHttpServletRequestBuilder> requestSupplier) throws Exception {
        mockMvc.perform(requestSupplier.get()
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isBadRequest());
    }

    private static Stream<Arguments> nonConvertibleIdSegments() {
        return Stream.of(
                Arguments.of("nonNumeric", "abc"),
                // A common client-side bug: JS stringifies undefined/null into the URL itself
                // (e.g. `/api/ingredients/${maybeUndefinedId}`) instead of never sending the
                // request - "null" the string, not a missing/absent path segment.
                Arguments.of("literalNullString", "null")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonConvertibleIdSegments")
    void delete_nonConvertibleId_returns400NotUnexpected500(String caseName, String idSegment) throws Exception {
        // Regression test: a path segment that fails Long conversion never reaches @Positive -
        // it fails during Spring's own argument resolution instead, exercising
        // GlobalExceptionHandler.handleTypeMismatch end to end through the real
        // @RestControllerAdvice wiring. Without it this used to fall through to the catch-all
        // handler and misreport a client input error as a 500.
        mockMvc.perform(delete("/api/ingredients/" + idSegment)
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'id': " + idSegment));
    }

    @Test
    void movements_cashierRole_returns403() throws Exception {
        // inventory-service is ADMIN-only end to end (see its SecurityConfig doc comment).
        mockMvc.perform(get("/api/ingredients/7/movements")
                        .header("X-Username", "cashier")
                        .header("X-User-Role", "CASHIER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void findAll_returns200WithIngredientList() throws Exception {
        when(ingredientService.findAll()).thenReturn(List.of(sampleIngredient()));

        MvcResult result = mockMvc.perform(get("/api/ingredients")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andReturn();

        assertIngredientResponse(objectMapper.readTree(result.getResponse().getContentAsString()).get(0));
    }

    @Test
    void create_validRequest_returns201WithIngredient() throws Exception {
        when(ingredientService.create(any())).thenReturn(sampleIngredient());

        MvcResult result = mockMvc.perform(post("/api/ingredients")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Milk\",\"unit\":\"liter\",\"minStock\":2,\"active\":true}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertIngredientResponse(objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    @Test
    void update_validRequest_returns200WithIngredient() throws Exception {
        when(ingredientService.update(eq(7L), any())).thenReturn(sampleIngredient());

        MvcResult result = mockMvc.perform(put("/api/ingredients/7")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Milk\",\"unit\":\"liter\",\"minStock\":2,\"active\":true}"))
                .andExpect(status().isOk())
                .andReturn();

        assertIngredientResponse(objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    @Test
    void delete_validId_returns200() throws Exception {
        mockMvc.perform(delete("/api/ingredients/7")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void stockIn_validRequest_returns200WithIngredient() throws Exception {
        when(ingredientService.stockIn(eq(7L), any())).thenReturn(sampleIngredient());

        MvcResult result = mockMvc.perform(post("/api/ingredients/7/stock-in")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5.5}"))
                .andExpect(status().isOk())
                .andReturn();

        assertIngredientResponse(objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    @Test
    void movements_validId_returns200WithMovementList() throws Exception {
        StockMovement movement = StockMovement.builder()
                .id(1L)
                .ingredient(sampleIngredient())
                .changeAmount(BigDecimal.valueOf(5.5))
                .reason("STOCK_IN")
                .referenceId("ref-1")
                .createdAt(Instant.now())
                .build();
        when(ingredientService.findMovements(7L)).thenReturn(List.of(movement));

        MvcResult result = mockMvc.perform(get("/api/ingredients/7/movements")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()).get(0);
        assertAll(
                () -> assertThat(body.get("id").asLong()).isEqualTo(1L),
                () -> assertThat(body.get("changeAmount").asDouble()).isEqualTo(5.5),
                () -> assertThat(body.get("reason").asText()).isEqualTo("STOCK_IN"),
                () -> assertThat(body.get("referenceId").asText()).isEqualTo("ref-1"),
                () -> assertThat(body.get("createdAt").asText()).isNotBlank()
        );
    }

    private void assertIngredientResponse(JsonNode body) {
        assertAll(
                () -> assertThat(body.get("id").asLong()).isEqualTo(7L),
                () -> assertThat(body.get("name").asText()).isEqualTo("Milk"),
                () -> assertThat(body.get("unit").asText()).isEqualTo("liter"),
                () -> assertThat(body.get("currentStock").asDouble()).isEqualTo(10.0),
                () -> assertThat(body.get("minStock").asDouble()).isEqualTo(2.0),
                () -> assertThat(body.get("reservedQuantity").asDouble()).isEqualTo(0.0),
                () -> assertThat(body.get("lowStock").asBoolean()).isFalse(),
                () -> assertThat(body.get("active").asBoolean()).isTrue()
        );
    }
}
