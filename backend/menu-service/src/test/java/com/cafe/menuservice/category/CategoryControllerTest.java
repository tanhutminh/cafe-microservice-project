package com.cafe.menuservice.category;

import com.cafe.menuservice.config.SecurityConfig;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** @WebMvcTest slice test - see order-service's OrderControllerTest for the pattern this mirrors. */
@WebMvcTest(controllers = CategoryController.class)
@Import(SecurityConfig.class)
class CategoryControllerTest {

    private static final String VALID_BODY = "{\"name\":\"Coffee\",\"displayOrder\":1,\"active\":true}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CategoryService categoryService;

    private Category sampleCategory() {
        return Category.builder().id(1L).name("Coffee").displayOrder(1).active(true).build();
    }

    private static Stream<Arguments> validationFailures() {
        return Stream.of(
                Arguments.of("update_negativeId",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/categories/-1")
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY),
                        "id", "must be greater than 0"),
                Arguments.of("update_negativeDisplayOrder",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/categories/1")
                                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Coffee\",\"displayOrder\":-1,\"active\":true}"),
                        "displayOrder", "must be greater than or equal to 0"),
                Arguments.of("update_blankName",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/categories/1")
                                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"displayOrder\":1,\"active\":true}"),
                        "name", "must not be blank"),
                Arguments.of("delete_negativeId",
                        (Supplier<MockHttpServletRequestBuilder>) () -> delete("/api/categories/-1"),
                        "id", "must be greater than 0")
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
                // (e.g. `/api/categories/${maybeUndefinedId}`) instead of never sending the
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
        mockMvc.perform(delete("/api/categories/" + idSegment)
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'id': " + idSegment));
    }

    @Test
    void findAll_returns200WithCategoryList() throws Exception {
        when(categoryService.findAll()).thenReturn(List.of(sampleCategory()));

        MvcResult result = mockMvc.perform(get("/api/categories")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andReturn();

        assertCategoryResponse(objectMapper.readTree(result.getResponse().getContentAsString()).get(0));
    }

    private static Stream<Arguments> happyPathRequests() {
        return Stream.of(
                Arguments.of("create",
                        (Supplier<MockHttpServletRequestBuilder>) () -> post("/api/categories")
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY),
                        HttpStatus.CREATED),
                Arguments.of("update",
                        (Supplier<MockHttpServletRequestBuilder>) () -> put("/api/categories/1")
                                .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY),
                        HttpStatus.OK)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("happyPathRequests")
    void validRequest_returnsExpectedStatusWithCategory(
            String caseName, Supplier<MockHttpServletRequestBuilder> requestSupplier, HttpStatus expectedStatus) throws Exception {
        if ("create".equals(caseName)) {
            when(categoryService.create(anyString(), anyInt(), anyBoolean())).thenReturn(sampleCategory());
        } else {
            when(categoryService.update(eq(1L), anyString(), anyInt(), anyBoolean())).thenReturn(sampleCategory());
        }

        MvcResult result = mockMvc.perform(requestSupplier.get()
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().is(expectedStatus.value()))
                .andReturn();

        assertCategoryResponse(objectMapper.readTree(result.getResponse().getContentAsString()));
    }

    @Test
    void delete_validId_returns204() throws Exception {
        mockMvc.perform(delete("/api/categories/1")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isNoContent());
    }

    private void assertCategoryResponse(JsonNode body) {
        assertAll(
                () -> assertThat(body.get("id").asLong()).isEqualTo(1L),
                () -> assertThat(body.get("name").asText()).isEqualTo("Coffee"),
                () -> assertThat(body.get("displayOrder").asInt()).isEqualTo(1),
                () -> assertThat(body.get("active").asBoolean()).isTrue()
        );
    }
}
