package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.AccountNotActiveException;
import com.cagritasoz.user_service.exception.EmailNotVerifiedException;
import com.cagritasoz.user_service.exception.MissingIdentityClaimException;
import com.cagritasoz.user_service.model.UserStatus;
import com.cagritasoz.user_service.repository.UserRepository;
import com.cagritasoz.user_service.testsupport.JwtFixtures;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class UserProvisioningServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserProvisioningService provisioningService;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // A single @Test method can only check ONE fixed scenario. This behavior - "any status other
    // than ACTIVE gets rejected" - is really the SAME check repeated for every non-ACTIVE value of
    // UserStatus (currently DELETING and DELETED). @ParameterizedTest replaces @Test on a method
    // that takes a parameter, and a "source" annotation (@EnumSource here) tells JUnit what values
    // to run that one method body with - once per value, each run reported as its own separate
    // pass/fail, not lumped together. @EnumSource(mode = EXCLUDE, names = "ACTIVE") means "every
    // UserStatus constant except ACTIVE" - so this runs twice, once with status = DELETING and
    // once with status = DELETED, without two near-identical @Test methods. The (name = "...")
    // customizes what each individual run is labelled as in the test report/output, using {0} for
    // the first (and here, only) parameter - so a failure clearly says which status failed, e.g.
    // "ensureUsable_nonActiveStatus_throwsAccountNotActive[status=DELETING]".
    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = UserStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void ensureUsable_nonActiveStatus_throwsAccountNotActive(UserStatus status) {

        User user = UserFixtures.activeUser().id(USER_ID).status(status).build();
        Jwt token = JwtFixtures.validUser(USER_ID).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    void provision_missingEmailClaim_throwsMissingIdentityClaim() {

        Jwt token = JwtFixtures.minimalToken(USER_ID).build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(MissingIdentityClaimException.class)
                .hasMessage("email");
    }

    @Test
    void provision_missingEmailVerifiedClaim_throwsMissingIdentityClaim() {

        Jwt token = JwtFixtures.minimalToken(USER_ID).claim("email", "john.marston@example.com").build();

        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioningService.ensureUsable(token))
                .isInstanceOf(EmailNotVerifiedException.class);
    }






}
