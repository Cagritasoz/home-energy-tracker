package com.cagritasoz.user_service.user;

import com.cagritasoz.user_service.testsupport.data.UserFixtures;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserIdentityTest {

    @Test
    void equals_sameId_isEqualWhateverTheOtherFieldsHold() {
        UUID id = UUID.randomUUID();
        User first = UserFixtures.activeUser().id(id).build();
        User second = UserFixtures.activeUser().id(id).email(UserFixtures.LEON_KENNEDY_EMAIL)
                .displayName(UserFixtures.LEON_KENNEDY_NAME).status(UserStatus.DELETION_SCHEDULED).version(7L).build();

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void equals_differentId_isNotEqual() {
        assertThat(UserFixtures.activeUser().build()).isNotEqualTo(UserFixtures.activeUser().build());
    }

    @Test
    void equals_nullAndOtherTypes_areNotEqual() {
        User user = UserFixtures.activeUser().build();

        assertThat(user).isNotEqualTo(null).isNotEqualTo(user.getId().toString());
    }

    @Test
    void hashCode_survivesEditsToTheEntity() {
        User user = UserFixtures.activeUser().build();
        Set<User> set = new HashSet<>(Set.of(user));
        int before = user.hashCode();

        user.setEmail(UserFixtures.JOHN_MARSTON_EMAIL);
        user.setDisplayName(UserFixtures.JOHN_MARSTON_NAME);
        user.setStatus(UserStatus.DELETING);
        user.setVersion(user.getVersion() + 1);

        assertThat(user.hashCode()).isEqualTo(before);
        assertThat(set).contains(user);
    }

    @Test
    void toString_namesTheRowAndItsState_neverTheEmailOrTheName() {
        User user = UserFixtures.activeUser().build();

        assertThat(user.toString())
                .contains(user.getId().toString(), "ACTIVE")
                .doesNotContain(UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);
    }
}
