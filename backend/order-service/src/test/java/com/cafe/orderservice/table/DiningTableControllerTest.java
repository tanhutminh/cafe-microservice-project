package com.cafe.orderservice.table;

import com.cafe.orderservice.config.SecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

/** @WebMvcTest slice test - see OrderControllerTest for the pattern this mirrors. */
@WebMvcTest(controllers = DiningTableController.class)
@Import(SecurityConfig.class)
class DiningTableControllerTest {

    private static final String VALID_BODY = "{\"tableNumber\":\"T3\",\"capacity\":4,\"active\":true}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DiningTableService diningTableService;

    private DiningTable sampleTable() {
        return DiningTable.builder().id(3L).tableNumber("Bàn 3").capacity(4).status(TableStatus.AVAILABLE).active(true).build();
    }

    /** One case per constraint on DiningTableRequest (tableNumber/capacity) plus @Positive on
     *  every endpoint's id path variable - not just a representative subset. */
    private static Stream<Arguments> validationFailures() {
        return Stream.of(
                Arguments.of("update_negativeId",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/tables/-1")
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY),
                        "id", "must be greater than 0"),
                Arguments.of("update_missingTableNumber",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/tables/3")
                                .contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":4,\"active\":true}"),
                        "tableNumber", "must not be blank"),
                Arguments.of("update_zeroCapacity",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/tables/3")
                                .contentType(MediaType.APPLICATION_JSON).content("{\"tableNumber\":\"T3\",\"capacity\":0,\"active\":true}"),
                        "capacity", "must be greater than or equal to 1"),
                Arguments.of("delete_negativeId",
                        (Supplier<MockHttpServletRequestBuilder>) () -> delete("/api/tables/-1"),
                        "id", "must be greater than 0"),
                Arguments.of("release_negativeId",
                        (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/tables/-1/release"),
                        "id", "must be greater than 0"),
                Arguments.of("create_missingTableNumber",
                        (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/tables")
                                .contentType(MediaType.APPLICATION_JSON).content("{\"capacity\":4,\"active\":true}"),
                        "tableNumber", "must not be blank"),
                Arguments.of("create_zeroCapacity",
                        (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/tables")
                                .contentType(MediaType.APPLICATION_JSON).content("{\"tableNumber\":\"T3\",\"capacity\":0,\"active\":true}"),
                        "capacity", "must be greater than or equal to 1")
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

    private static Stream<Arguments> nonConvertibleIdSegments() {
        return Stream.of(
                Arguments.of("nonNumeric", "abc"),
                // A common client-side bug: JS stringifies undefined/null into the URL itself
                // (e.g. `/api/tables/${maybeUndefinedId}`) instead of never sending the request -
                // "null" the string, not a missing/absent path segment.
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
        mockMvc.perform(delete("/api/tables/" + idSegment)
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'id': " + idSegment));
    }

    @Test
    void delete_validId_returns200() throws Exception {
        mockMvc.perform(delete("/api/tables/3")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk());
    }

    @Test
    void release_missingAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/tables/3/release"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void release_cashierRole_returns200() throws Exception {
        // order-service's SecurityConfig grants /api/tables/** to both ADMIN and CASHIER -
        // CASHIER's whole job is the POS surface (tables + orders), unlike inventory-service.
        when(diningTableService.findById(3L)).thenReturn(sampleTable());

        mockMvc.perform(post("/api/tables/3/release")
                        .header("X-Username", "cashier")
                        .header("X-User-Role", "CASHIER"))
                .andExpect(status().isOk());
    }

    @Test
    void findAll_returns200WithTableList() throws Exception {
        when(diningTableService.findAll()).thenReturn(List.of(sampleTable()));

        MvcResult result = mockMvc.perform(get("/api/tables")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "CASHIER"))
                .andExpect(status().isOk())
                .andReturn();

        assertTableResponse(objectMapper.readTree(result.getResponse().getContentAsString()).get(0));
    }

    private static Stream<Arguments> happyPathRequests() {
        return Stream.of(
                Arguments.of("create",
                        (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/tables")
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY),
                        HttpStatus.CREATED),
                Arguments.of("update",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/tables/3")
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY),
                        HttpStatus.OK),
                Arguments.of("release",
                        (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/tables/3/release"), HttpStatus.OK)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("happyPathRequests")
    void validRequest_returnsExpectedStatusWithTable(
            String caseName, Supplier<MockHttpServletRequestBuilder> requestSupplier, HttpStatus expectedStatus) throws Exception {
        switch (caseName) {
            case "create" -> when(diningTableService.create(any())).thenReturn(sampleTable());
            case "update" -> when(diningTableService.update(eq(3L), any())).thenReturn(sampleTable());
            case "release" -> when(diningTableService.findById(3L)).thenReturn(sampleTable());
        }

        MvcResult result = mockMvc.perform(requestSupplier.get()
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().is(expectedStatus.value()))
                .andReturn();

        assertTableResponse(objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    private void assertTableResponse(JsonNode body) {
        assertAll(
                () -> assertThat(body.get("id").asLong()).isEqualTo(3L),
                () -> assertThat(body.get("tableNumber").asText()).isEqualTo("Bàn 3"),
                () -> assertThat(body.get("capacity").asInt()).isEqualTo(4),
                () -> assertThat(body.get("status").asText()).isEqualTo("AVAILABLE"),
                () -> assertThat(body.get("active").asBoolean()).isTrue()
        );
    }
}
