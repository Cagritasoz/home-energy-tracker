package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.testsupport.config.ConfigFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// A typo in app.user.deletion.required-participants must stop the application from starting: otherwise it is copied
// into every deletion's participant rows, nobody can ever confirm it, and every deletion waits forever.
class RequiredParticipantsValidatorTest {

    private static final ParticipantAcks ACKS = new ParticipantAcks(List.of(
            new ParticipantAck("devices", "device.events.v1", "UserDevicesUnclaimed", 1),
            new ParticipantAck("usage", "usage.events.v1", "UserReadingsDeleted", 1)));

    private static RequiredParticipantsValidator validator(String... required) {

        return new RequiredParticipantsValidator(
                ConfigFixtures.deletion("required-participants=" + String.join(",", required)), ACKS);

    }

    @Test
    void constructor_emptySet_isFine() {

        assertThatCode(RequiredParticipantsValidatorTest::validator).doesNotThrowAnyException();

    }

    @Test
    void constructor_everyNameHasAHandler_isFine() {

        assertThatCode(() -> validator("devices")).doesNotThrowAnyException();
        assertThatCode(() -> validator("usage", "devices")).doesNotThrowAnyException();

    }

    @Test
    void constructor_nameWithoutAHandler_refusesAndNamesIt() {

        assertThatThrownBy(() -> validator("devices", "device"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.user.deletion.required-participants")
                .hasMessageContaining("[device]")
                .hasMessageContaining("devices");

    }

    @Test
    void constructor_everyNameUnknown_namesAllOfThem() {

        assertThatThrownBy(() -> validator("alerts", "notifications"))
                .hasMessageContaining("alerts").hasMessageContaining("notifications");

    }

    // The same check with the real wiring: the property, the production registry and Spring's start-up. A typo is a
    // failed start, not a runtime surprise.
    @Test
    void application_startsWithADeviceRequiredAndFailsOnATypo() {

        // application.yaml is loaded the way the application loads it, so the rest of DeletionProperties is there and
        // only the list under test is overridden.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ParticipantAcksConfig.class, RequiredParticipantsValidator.class);

        runner.withPropertyValues("app.user.deletion.required-participants=devices")
                .run(context -> assertThat(context).hasNotFailed());

        runner.withPropertyValues("app.user.deletion.required-participants=")
                .run(context -> assertThat(context).hasNotFailed());

        runner.withPropertyValues("app.user.deletion.required-participants=devicez")
                .run(context -> assertThat(context).hasFailed());

    }
}
