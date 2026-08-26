package com.cafe.menuservice.menuitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cafe.menuservice.category.Category;
import com.cafe.menuservice.config.SecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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

/**
 * @WebMvcTest slice test - see order-service's OrderControllerTest for the pattern this mirrors.
 */
@WebMvcTest(controllers = MenuItemController.class)
@Import(SecurityConfig.class)
class MenuItemControllerTest {

  private static final String VALID_BODY =
      "{\"categoryId\":1,\"name\":\"Cappuccino\",\"price\":45000,\"available\":true,\"active\":true}";

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private MenuItemService menuItemService;

  private MenuItem sampleItem() {
    return sampleItem(12L, "Cappuccino");
  }

  private MenuItem sampleItem(Long id, String name) {
    Category category =
        Category.builder().id(1L).name("Coffee").displayOrder(1).active(true).build();
    return MenuItem.builder()
        .id(id)
        .category(category)
        .name(name)
        .description("Foamy")
        .price(BigDecimal.valueOf(45000))
        .imageUrl(null)
        .available(true)
        .active(true)
        .build();
  }

  /**
   * Covers @Positive on the id path variable/categoryId query param, every constraint on
   * MenuItemRequest (categoryId/name/description/price/imageUrl), and @NotNull on
   * UpdateAvailabilityRequest.available - one case per constraint, not just a representative
   * subset, since a passing "happy path plus one generic error case" test can hit 100% line
   * coverage while still missing individual field constraints.
   */
  private static Stream<Arguments> validationFailures() {
    return Stream.of(
        Arguments.of(
            "update_negativeId",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    put("/api/menu-items/-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY),
            "id",
            "must be greater than 0"),
        Arguments.of(
            "search_negativeCategoryId",
            (Supplier<MockHttpServletRequestBuilder>)
                () -> get("/api/menu-items").param("categoryId", "-1"),
            "categoryId",
            "must be greater than 0"),
        Arguments.of(
            "create_missingCategoryId",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/menu-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"name\":\"Cappuccino\",\"price\":45000,\"available\":true,\"active\":true}"),
            "categoryId",
            "must not be null"),
        Arguments.of(
            "create_negativeCategoryId",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/menu-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"categoryId\":-1,\"name\":\"Cappuccino\",\"price\":45000,\"available\":true,\"active\":true}"),
            "categoryId",
            "must be greater than 0"),
        Arguments.of(
            "create_blankName",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/menu-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"categoryId\":1,\"name\":\"\",\"price\":45000,\"available\":true,\"active\":true}"),
            "name",
            "must not be blank"),
        Arguments.of(
            "create_descriptionTooLong",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/menu-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"categoryId\":1,\"name\":\"Cappuccino\",\"description\":\""
                                + "a".repeat(2001)
                                + "\",\"price\":45000,\"available\":true,\"active\":true}"),
            "description",
            "size must be between 0 and 2000"),
        Arguments.of(
            "create_missingPrice",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/menu-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"categoryId\":1,\"name\":\"Cappuccino\",\"available\":true,\"active\":true}"),
            "price",
            "must not be null"),
        Arguments.of(
            "create_negativePrice",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/menu-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"categoryId\":1,\"name\":\"Cappuccino\",\"price\":-1,\"available\":true,\"active\":true}"),
            "price",
            "must be greater than or equal to 0"),
        Arguments.of(
            "create_imageUrlTooLong",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/menu-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"categoryId\":1,\"name\":\"Cappuccino\",\"price\":45000,\"imageUrl\":\""
                                + "a".repeat(501)
                                + "\",\"available\":true,\"active\":true}"),
            "imageUrl",
            "size must be between 0 and 500"),
        Arguments.of(
            "updateAvailability_missingAvailableField",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    patch("/api/menu-items/12/availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"),
            "available",
            "must not be null"),
        Arguments.of(
            "findAllById_negativeId",
            (Supplier<MockHttpServletRequestBuilder>)
                () -> get("/api/menu-items/batch").param("ids", "-1"),
            "ids",
            "must be greater than 0"),
        Arguments.of(
            "findAllById_tooManyIds",
            (Supplier<MockHttpServletRequestBuilder>)
                () -> get("/api/menu-items/batch").param("ids", tooManyIds()),
            "ids",
            "size must be between 0 and 50"));
  }

  private static String[] tooManyIds() {
    return IntStream.rangeClosed(1, 51).mapToObj(String::valueOf).toArray(String[]::new);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("validationFailures")
  void invalidRequest_returns400WithFieldViolation(
      String caseName,
      Supplier<MockHttpServletRequestBuilder> requestSupplier,
      String expectedField,
      String expectedMessage)
      throws Exception {
    mockMvc
        .perform(requestSupplier.get().header("X-Username", "admin").header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.validationErrors[0].field").value(expectedField))
        .andExpect(jsonPath("$.validationErrors[0].message").value(expectedMessage));
  }

  private static Stream<Arguments> nonConvertibleIdSegments() {
    return Stream.of(
        Arguments.of("nonNumeric", "abc"),
        // A common client-side bug: JS stringifies undefined/null into the URL itself
        // (e.g. `/api/menu-items/${maybeUndefinedId}`) instead of never sending the
        // request - "null" the string, not a missing/absent path segment.
        Arguments.of("literalNullString", "null"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("nonConvertibleIdSegments")
  void update_nonConvertibleId_returns400NotUnexpected500(String caseName, String idSegment)
      throws Exception {
    // Regression test: a path segment that fails Long conversion never reaches @Positive -
    // it fails during Spring's own argument resolution instead, exercising
    // GlobalExceptionHandler.handleTypeMismatch end to end through the real
    // @RestControllerAdvice wiring. Without it this used to fall through to the catch-all
    // handler and misreport a client input error as a 500.
    mockMvc
        .perform(
            put("/api/menu-items/" + idSegment)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY)
                .header("X-Username", "admin")
                .header("X-User-Role", "ADMIN"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Invalid value for 'id': " + idSegment));
  }

  @Test
  void search_returns200WithMenuItemList() throws Exception {
    when(menuItemService.search(null, null)).thenReturn(List.of(sampleItem()));

    MvcResult result =
        mockMvc
            .perform(
                get("/api/menu-items").header("X-Username", "admin").header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk())
            .andReturn();

    assertMenuItemResponse(objectMapper.readTree(result.getResponse().getContentAsString()).get(0));
  }

  @Test
  void findAllById_returns200WithEveryMatchingMenuItem() throws Exception {
    MenuItem espresso = sampleItem(34L, "Espresso");
    when(menuItemService.findAllById(List.of(12L, 34L)))
        .thenReturn(List.of(sampleItem(), espresso));

    MvcResult result =
        mockMvc
            .perform(
                get("/api/menu-items/batch")
                    .param("ids", "12", "34")
                    .header("X-Username", "admin")
                    .header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk())
            .andReturn();

    JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
    assertAll(
        () -> assertThat(body.size()).isEqualTo(2),
        () -> assertMenuItemResponse(body.get(0)),
        () -> assertThat(body.get(1).get("id").asLong()).isEqualTo(34L),
        () -> assertThat(body.get(1).get("name").asText()).isEqualTo("Espresso"));
  }

  private static Stream<Arguments> happyPathRequests() {
    return Stream.of(
        Arguments.of(
            "create",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    post("/api/menu-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY),
            HttpStatus.CREATED),
        Arguments.of(
            "update",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    put("/api/menu-items/12")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY),
            HttpStatus.OK),
        Arguments.of(
            "updateAvailability",
            (Supplier<MockHttpServletRequestBuilder>)
                () ->
                    patch("/api/menu-items/12/availability")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"available\":false}"),
            HttpStatus.OK));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("happyPathRequests")
  void validRequest_returnsExpectedStatusWithMenuItem(
      String caseName,
      Supplier<MockHttpServletRequestBuilder> requestSupplier,
      HttpStatus expectedStatus)
      throws Exception {
    switch (caseName) {
      case "create" -> when(menuItemService.create(any())).thenReturn(sampleItem());
      case "update" -> when(menuItemService.update(eq(12L), any())).thenReturn(sampleItem());
      case "updateAvailability" ->
          when(menuItemService.updateAvailability(12L, false)).thenReturn(sampleItem());
    }

    MvcResult result =
        mockMvc
            .perform(
                requestSupplier.get().header("X-Username", "admin").header("X-User-Role", "ADMIN"))
            .andExpect(status().is(expectedStatus.value()))
            .andReturn();

    assertMenuItemResponse(objectMapper.readTree(result.getResponse().getContentAsString()));
  }

  private void assertMenuItemResponse(JsonNode body) {
    assertAll(
        () -> assertThat(body.get("id").asLong()).isEqualTo(12L),
        () -> assertThat(body.get("categoryId").asLong()).isEqualTo(1L),
        () -> assertThat(body.get("categoryName").asText()).isEqualTo("Coffee"),
        () -> assertThat(body.get("name").asText()).isEqualTo("Cappuccino"),
        () -> assertThat(body.get("description").asText()).isEqualTo("Foamy"),
        () -> assertThat(body.get("price").asDouble()).isEqualTo(45000.0),
        () -> assertThat(body.get("imageUrl").isNull()).isTrue(),
        () -> assertThat(body.get("available").asBoolean()).isTrue(),
        () -> assertThat(body.get("active").asBoolean()).isTrue());
  }
}
