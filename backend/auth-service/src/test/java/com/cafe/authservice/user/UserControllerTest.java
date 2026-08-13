package com.cafe.authservice.user;

import com.cafe.authservice.config.SecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice test - see order-service's OrderControllerTest for the pattern this mirrors.
 * src/test/resources/application.yml shadows the real one (required "configserver:" import),
 * so this slice context doesn't need config-server running.
 */
@WebMvcTest(controllers = UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        sampleUser = User.builder().id(3L).username("cashier2").passwordHash("hash").fullName("Nguyen Van A")
                .role(Role.CASHIER).active(false).build();
    }

    private static Stream<Arguments> updateStatusValidationFailures() {
        return Stream.of(
                Arguments.of("negativeId", "/api/users/-1/status", "{\"active\":false}", "id"),
                Arguments.of("missingActive", "/api/users/3/status", "{}", "active")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateStatusValidationFailures")
    void updateStatus_invalidRequest_returns400WithFieldViolation(String caseName, String url, String body, String expectedField) throws Exception {
        mockMvc.perform(patch(url)
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors[0].field").value(expectedField));
    }

    private static Stream<Arguments> updateStatusAuthFailures() {
        return Stream.of(
                Arguments.of("wrongRole_cashier", Map.of("X-Username", "cashier", "X-User-Role", "CASHIER"), HttpStatus.FORBIDDEN),
                Arguments.of("missingAuthHeaders", Map.of(), HttpStatus.UNAUTHORIZED)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("updateStatusAuthFailures")
    void updateStatus_insufficientAuth_returnsExpectedStatus(String caseName, Map<String, String> headers, HttpStatus expectedStatus) throws Exception {
        MockHttpServletRequestBuilder requestBuilder = patch("/api/users/3/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}");
        headers.forEach(requestBuilder::header);

        mockMvc.perform(requestBuilder)
                .andExpect(status().is(expectedStatus.value()));
    }

    private static Stream<Arguments> nonConvertibleIdSegments() {
        return Stream.of(
                Arguments.of("nonNumeric", "abc"),
                // A common client-side bug: JS stringifies undefined/null into the URL itself
                // (e.g. `/api/users/${maybeUndefinedId}/status`) instead of never sending the
                // request - "null" the string, not a missing/absent path segment.
                Arguments.of("literalNullString", "null")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonConvertibleIdSegments")
    void updateStatus_nonConvertibleId_returns400NotUnexpected500(String caseName, String idSegment) throws Exception {
        // Regression test: a path segment that fails Long conversion never reaches @Positive -
        // it fails during Spring's own argument resolution instead, exercising
        // GlobalExceptionHandler.handleTypeMismatch end to end through the real
        // @RestControllerAdvice wiring. Without it this used to fall through to the catch-all
        // handler and misreport a client input error as a 500.
        mockMvc.perform(patch("/api/users/" + idSegment + "/status")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'id': " + idSegment));
    }

    @Test
    void updateStatus_validRequest_returns200WithUpdatedUser() throws Exception {
        when(userService.setActive(3L, false)).thenReturn(sampleUser);

        MvcResult result = mockMvc.perform(patch("/api/users/3/status")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andReturn();

        assertUserResponseBody(result, 3L, "cashier2", "Nguyen Van A", "CASHIER", false);
    }

    @Test
    void findAll_returns200WithUserList() throws Exception {
        when(userService.findAll()).thenReturn(List.of(sampleUser));

        MvcResult result = mockMvc.perform(get("/api/users")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString()).get(0);
        assertAll(
                () -> assertThat(body.get("id").asLong()).isEqualTo(3L),
                () -> assertThat(body.get("username").asText()).isEqualTo("cashier2"),
                () -> assertThat(body.get("fullName").asText()).isEqualTo("Nguyen Van A"),
                () -> assertThat(body.get("role").asText()).isEqualTo("CASHIER"),
                () -> assertThat(body.get("active").asBoolean()).isFalse()
        );
    }

    @Test
    void create_validRequest_returns201WithUser() throws Exception {
        when(userService.create("cashier2", "a-strong-password", "Nguyen Van A", Role.CASHIER)).thenReturn(sampleUser);

        MvcResult result = mockMvc.perform(post("/api/users")
                        .header("X-Username", "admin")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"cashier2\",\"password\":\"a-strong-password\",\"fullName\":\"Nguyen Van A\",\"role\":\"CASHIER\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertUserResponseBody(result, 3L, "cashier2", "Nguyen Van A", "CASHIER", false);
    }

    private void assertUserResponseBody(MvcResult result, long id, String username, String fullName, String role, boolean active) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertAll(
                () -> assertThat(body.get("id").asLong()).isEqualTo(id),
                () -> assertThat(body.get("username").asText()).isEqualTo(username),
                () -> assertThat(body.get("fullName").asText()).isEqualTo(fullName),
                () -> assertThat(body.get("role").asText()).isEqualTo(role),
                () -> assertThat(body.get("active").asBoolean()).isEqualTo(active)
        );
    }
}
