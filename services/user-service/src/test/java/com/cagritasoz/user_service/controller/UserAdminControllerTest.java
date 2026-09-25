package com.cagritasoz.user_service.controller;

import com.cagritasoz.user_service.dto.UserAdminResponse;
import com.cagritasoz.user_service.exception.GlobalExceptionHandler;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.model.UserStatus;
import com.cagritasoz.user_service.service.UserAdminService;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same standalone-MockMvc approach as UserControllerTest (see the comment there for what that does
// and doesn't include). One gap specific to this controller: @PreAuthorize("hasRole('ADMIN')") and
// SecurityConfig's URL rule for /api/v1/admin/** are NOT exercised here - method security and the
// filter chain only exist inside a real security-enabled Spring context, so nothing in this class
// can prove a non-admin is turned away. That needs a heavier test later.
@ExtendWith(MockitoExtension.class)
public class UserAdminControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserAdminService userAdminService;

    private MockMvc mockMvc;

    // No need to set a CustomArgumentResolver as @PathVariable and @RequestParam are handled by standard resolvers.
    @BeforeEach
    void setUpMockMvc() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UserAdminController(userAdminService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private UserAdminResponse activeUserAdminResponse() {
        return UserAdminResponse.builder()
                .id(USER_ID)
                .email(UserFixtures.ARTHUR_MORGAN_EMAIL)
                .displayName(UserFixtures.ARTHUR_MORGAN_NAME)
                .timezone("UTC")
                .status(UserStatus.ACTIVE)
                .version(0L)
                .devicesDeleted(false)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build();
    }

    // The same person as above, but in the DELETED state, so every deletion-saga column carries a
    // real value - what the getUser test needs to prove all twelve admin fields are exposed.
    private UserAdminResponse deletedUserAdminResponse() {
        return UserAdminResponse.builder()
                .id(USER_ID)
                .email(UserFixtures.ARTHUR_MORGAN_EMAIL)
                .displayName(UserFixtures.ARTHUR_MORGAN_NAME)
                .timezone("UTC")
                .status(UserStatus.DELETED)
                .version(12L)
                .devicesDeleted(true)
                .keycloakDisabledAt(Instant.parse("2026-02-01T00:00:00Z"))
                .deletionRequestedAt(Instant.parse("2026-01-31T23:58:42Z"))
                .deletedAt(Instant.parse("2026-02-01T00:00:05Z"))
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-02-01T00:00:05Z"))
                .build();
    }

    @Test
    void listUsers_noParams_usesDefaultPageAndSize() throws Exception {

        UserAdminResponse response = activeUserAdminResponse();

        when(userAdminService.listUsers(0, 20)).thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().isOk())
                // The controller's only real logic is wrapping the service's Page in a PagedModel,
                // so every field of that wrapper is checked: the content list and all four page
                // fields. These come from the stubbed Page above, not from the request - they prove
                // the wrapping, not the defaults (the stub and the verify below prove those).
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id").value(USER_ID.toString()))
                // A null column must still appear in the JSON as an explicit null, not be left out.
                .andExpect(jsonPath("$.content[0].keycloakDisabledAt").value((Object) null))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1));

        verify(userAdminService).listUsers(0, 20); // Verify it defaults to 0, 20.
    }

    @Test
    void listUsers_passedParams_reachesServiceCorrectly() throws Exception {

        when(userAdminService.listUsers(2, 50)).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/users?page=2&size=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        verify(userAdminService).listUsers(2, 50);
    }

    // Every way the paging parameters can be rejected, one whole query string per case. Plain
    // Strings rather than (int page, int size) because a query string is text on the wire: an int
    // can't represent "abc" or "1.5" at all. Using the whole query string also means a case with a
    // single bad parameter simply leaves the other at its default, with no placeholder needed.
    //
    // Two different exceptions end up here, both answered with a 400: @Min/@Max violations
    // (HandlerMethodValidationException) and values that aren't an int (MethodArgumentType-
    // MismatchException). Their "detail" text differs, so it isn't asserted in this shared test.
    // An EMPTY value ("size=") is deliberately not listed: Spring swaps it for the default, so it
    // is accepted, not rejected.
    @ParameterizedTest(name = "?{0}")
    @ValueSource(strings = {
            "page=-1",          // below @Min(0)
            "size=0",           // below @Min(1)
            "size=101",         // above @Max(100)
            "size=-5",
            "page=-1&size=30",  // a valid size doesn't rescue an invalid page
            "page=abc",         // not a number
            "size=abc",
            "size=1.5",         // a number, but not an int
            "size=99999999999"  // int-shaped, but too large for an int
    })
    void listUsers_invalidRequestParameters_returns400(String query) throws Exception {

        // The full ProblemDetail is checked here on purpose: this is the one place the inherited
        // Spring errors are proven to go through GlobalExceptionHandler.createResponseEntity end to
        // end - it used to crash on a null type and silently fall back to an empty-bodied 400, so a
        // missing type/timestamp is exactly what a regression would look like.
        mockMvc.perform(get("/api/v1/admin/users?" + query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/bad-request"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.timestamp").exists());

        // Nothing is stubbed above (strict stubs would flag an unused stub) - rejected requests
        // must never reach the service at all.
        verify(userAdminService, never()).listUsers(anyInt(), anyInt());
    }

    // The other side of the invalid cases above: values that sit exactly ON a limit, plus empty
    // values, must all be ACCEPTED. Each row is: query | page the service should receive | size the
    // service should receive. An empty value ("size=") is accepted and behaves like the default,
    // because Spring replaces an empty request parameter with its defaultValue - documented here
    // as real behavior, not something to fix.
    @ParameterizedTest(name = "?{0} -> page={1}, size={2}")
    @CsvSource(delimiter = '|', value = {
            "size=1   | 0 | 1",     // lowest allowed size (@Min(1))
            "size=100 | 0 | 100",   // highest allowed size (@Max(100))
            "page=0   | 0 | 20",    // lowest allowed page (@Min(0))
            "size=    | 0 | 20",    // empty -> default size
            "page=    | 0 | 20"     // empty -> default page
    })
    void listUsers_boundaryParams_returns200WithExpectedPaging(String query, int expectedPage, int expectedSize) throws Exception {

        when(userAdminService.listUsers(expectedPage, expectedSize))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(expectedPage, expectedSize), 0));

        mockMvc.perform(get("/api/v1/admin/users?" + query))
                .andExpect(status().isOk());

        verify(userAdminService).listUsers(expectedPage, expectedSize);
    }

    // Uses the DELETED variant so every column has a value. This is the admin view: unlike the
    // self-service response it exposes the whole deletion-saga state (status, version,
    // devicesDeleted and the three saga timestamps), and Instants come out as ISO-8601 strings.
    @Test
    void getUser_existingId_returnsAllAdminFields() throws Exception {

        when(userAdminService.getUser(USER_ID)).thenReturn(deletedUserAdminResponse());

        mockMvc.perform(get("/api/v1/admin/users/{id}", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value(UserFixtures.ARTHUR_MORGAN_EMAIL))
                .andExpect(jsonPath("$.displayName").value(UserFixtures.ARTHUR_MORGAN_NAME))
                .andExpect(jsonPath("$.timezone").value("UTC"))
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.version").value(12))
                .andExpect(jsonPath("$.devicesDeleted").value(true))
                .andExpect(jsonPath("$.keycloakDisabledAt").value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.deletionRequestedAt").value("2026-01-31T23:58:42Z"))
                .andExpect(jsonPath("$.deletedAt").value("2026-02-01T00:00:05Z"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.updatedAt").value("2026-02-01T00:00:05Z"));

        // The id in the URL is what reaches the service.
        verify(userAdminService).getUser(USER_ID);
    }

    // The main failure of a lookup by id. This is this endpoint's own contract (an admin asking
    // for an id that doesn't exist gets a 404), not a second proof that the advice is wired up -
    // UserControllerTest already covers that once.
    @Test
    void getUser_unknownId_returns404() throws Exception {

        when(userAdminService.getUser(USER_ID)).thenThrow(new UserNotFoundException());

        mockMvc.perform(get("/api/v1/admin/users/{id}", USER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(HttpStatus.NOT_FOUND.value()))
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/user-not-found"))
                .andExpect(jsonPath("$.title").value("User not found"))
                .andExpect(jsonPath("$.detail").value("User not found."));
    }

    // Ids that Spring's String -> UUID conversion rejects. Deliberately NOT included: "1-1-1-1-1"
    // and a UUID one character short in its last group - Java's UUID.fromString pads short groups,
    // so those are silently ACCEPTED as a different (valid) UUID rather than rejected.
    @ParameterizedTest(name = "id={0}")
    @ValueSource(strings = {
            "not-a-uuid",
            "123",
            "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz",     // right shape, but not hex digits
            "11111111111111111111111111111111",         // 32 hex digits, no dashes
            "11111111-1111-1111-1111-1111111111111"     // one character too long
    })
    void getUser_malformedId_returns400(String id) throws Exception {

        mockMvc.perform(get("/api/v1/admin/users/" + id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/bad-request"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Failed to convert 'id' with value: '" + id + "'"));

        verify(userAdminService, never()).getUser(any(UUID.class));
    }
}
