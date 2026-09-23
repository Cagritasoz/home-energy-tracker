package com.cagritasoz.user_service.controller;

import com.cagritasoz.user_service.dto.UpdateUserRequest;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.exception.GlobalExceptionHandler;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.service.UserService;
import com.cagritasoz.user_service.testsupport.JwtFixtures;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Layer 1-ish: real Spring MVC dispatch (argument resolution, @Valid, exception-handler dispatch),
// but no ApplicationContext - MockMvcBuilders.standaloneSetup(...) hand-assembles just enough
// machinery around ONE controller instance to route a request into it and back, nothing more.
// UserProvisioningInterceptor is never in this picture: it's registered via a real
// WebMvcConfigurer bean, which only exists inside a real ApplicationContext - so these tests start
// straight at the controller, as if provisioning had already happened. Not testing that here.
@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserService userService;

    private MockMvc mockMvc;

    // Turns a Java object into the JSON string a real request body would carry - needed because
    // MockMvc's .content(...) takes a raw String/byte[], not an object, unlike .andExpect(...) on
    // the response side, which reads JSON back out via jsonPath without you touching Jackson
    // directly at all. Jackson 3's package: tools.jackson.databind, not the classic
    // com.fasterxml.jackson.databind - matches ProblemDetailAuthenticationEntryPoint's own import.
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUpObjectMapper() {
        // Plain new ObjectMapper() rather than the app's real configured bean: this class has no
        // ApplicationContext to pull that bean from (see the class comment), and none of these
        // tests currently depend on any non-default Jackson behavior. A field initializer would
        // also have worked here specifically - unlike mockMvc, this doesn't depend on the @Mock
        // field, so there's no ordering problem - @BeforeEach is used anyway just to keep every
        // piece of setup in this class following the same shape.
        objectMapper = new ObjectMapper();
    }

    @BeforeEach
    void setUpMockMvc() {
        // .setControllerAdvice(...) wires GlobalExceptionHandler into this mini dispatcher, the
        // same way @RestControllerAdvice wires it into the real app - without this line, any
        // exception thrown here would just be an unhandled 500 with no ProblemDetail at all.
        //
        // .setCustomArgumentResolvers(...) turned out to be load-bearing, not optional: without
        // it, standalone MockMvc has no idea @AuthenticationPrincipal means "read from
        // SecurityContextHolder" - that wiring is normally auto-registered as part of Spring
        // Security's own web configuration, which a hand-built standalone dispatcher never loads.
        // Left out, Spring falls back to treating the Jwt parameter as a @ModelAttribute to be
        // data-bound from request parameters instead, and blows up trying to construct one via
        // reflection - confirmed by actually running this test without the line below first.
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    // @AuthenticationPrincipal reads from SecurityContextHolder, a static, thread-local holder -
    // there's no security filter chain in standalone mode to populate it for us, so each test does
    // it by hand: build a Jwt (JwtFixtures), wrap it in the same Authentication type Spring
    // Security itself uses for a validated bearer token (JwtAuthenticationToken), and set it as
    // "the current request's identity" before the request runs.
    @BeforeEach
    void authenticateAsArthurMorgan() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    // Being thread-local, this state survives past the end of a test unless something clears it -
    // and JUnit doesn't run each test method on a fresh thread by default, so a forgotten context
    // here would leak into whichever test runs next on the same thread. Always pair the @BeforeEach
    // above with this.
    @AfterEach
    void clearSecurityContext() {

        SecurityContextHolder.clearContext();

    }

    @Test
    void getMe_returnsCurrentUser() throws Exception {

        UserResponse response = UserResponse.builder()
                .id(USER_ID)
                .email(UserFixtures.ARTHUR_MORGAN_EMAIL)
                .displayName(UserFixtures.ARTHUR_MORGAN_NAME)
                .timezone("UTC")
                .build();

        when(userService.getUser(USER_ID)).thenReturn(response);

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value(UserFixtures.ARTHUR_MORGAN_EMAIL))
                .andExpect(jsonPath("$.displayName").value(UserFixtures.ARTHUR_MORGAN_NAME));

        // Proves the id came from the token's own "sub" claim, not from anywhere else in the
        // request - USER_ID here is literally JwtFixtures.validUser's subject.
        verify(userService).getUser(USER_ID);
    }

    // TODO: getMe_userNotFound_returns404 - stub userService.getUser to throw
    //       UserNotFoundException, confirm GlobalExceptionHandler's wiring (via
    //       .setControllerAdvice above) actually produces a 404 ProblemDetail through the full
    //       mini-stack, not just in GlobalExceptionHandlerTest's isolation.
    @Test
    void getMe_userNotFound_returns404() throws Exception {

        when(userService.getUser(USER_ID)).thenThrow(new UserNotFoundException());

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(HttpStatus.NOT_FOUND.value())) // Expected value is the message.
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/user-not-found"))
                .andExpect(jsonPath("$.title").value("User not found"))
                .andExpect(jsonPath("$.detail").value("User not found."));
    }

    @Test
    void updateMe_validPartialBody_returnsUpdatedUser() throws Exception {

        UserResponse response = UserResponse.builder()
                .id(USER_ID)
                .email(UserFixtures.ARTHUR_MORGAN_EMAIL)
                .displayName("aMorgan")
                .timezone("UTC")
                .build();

        UpdateUserRequest request = UpdateUserRequest.builder()
                .displayName("aMorgan")
                .build();

        when(userService.updateUser(USER_ID, request)).thenReturn(response);

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value(UserFixtures.ARTHUR_MORGAN_EMAIL))
                .andExpect(jsonPath("$.displayName").value("aMorgan"));

        verify(userService).updateUser(USER_ID, request);
    }

    // Tests globalExceptionHandler.handleMethodArgumentNotValid method.
    @Test
    void updateMe_blankDisplayName_returns400() throws Exception {

        UpdateUserRequest request = UpdateUserRequest.builder()
                .displayName("    ")
                .build();
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.displayName").value("must not be blank"))
                .andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value())) // Expected value is the message.
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/validation-failed"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.detail").value("Validation failed."));

        verify(userService, never()).updateUser(any(), any());
    }

    // Tests globalExceptionHandler.handleHttpMessageNotReadable method.
    @Test
    void updateMe_malformedJson_returns400() throws Exception {

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {invalid
                                """
                        ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(HttpStatus.BAD_REQUEST.value()))
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/malformed-request"))
                .andExpect(jsonPath("$.title").value("Malformed request"))
                .andExpect(jsonPath("$.detail").value("Malformed request body."));

        verify(userService, never()).updateUser(any(), any());
    }

    @Test
    void deleteMe_returns202() throws Exception {

        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));

        verify(userService).requestDeletion(USER_ID);
    }
}
