package com.cagritasoz.user_service.deletion.ack;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
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

    @Test
    void constructor_emptySet_isFine() {

        assertThatCode(() -> new RequiredParticipantsValidator(List.of(), ACKS)).doesNotThrowAnyException();

    }

    @Test
    void constructor_everyNameHasAHandler_isFine() {

        assertThatCode(() -> new RequiredParticipantsValidator(List.of("devices"), ACKS)).doesNotThrowAnyException();
        assertThatCode(() -> new RequiredParticipantsValidator(List.of("usage", "devices"), ACKS)).doesNotThrowAnyException();

    }

    @Test
    void constructor_nameWithoutAHandler_refusesAndNamesIt() {

        assertThatThrownBy(() -> new RequiredParticipantsValidator(List.of("devices", "device"), ACKS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.user.deletion.required-participants")
                .hasMessageContaining("[device]")
                .hasMessageContaining("devices");

    }

    @Test
    void constructor_everyNameUnknown_namesAllOfThem() {

        assertThatThrownBy(() -> new RequiredParticipantsValidator(List.of("alerts", "notifications"), ACKS))
                .hasMessageContaining("alerts").hasMessageContaining("notifications");

    }

    // The same check with the real wiring: the property, the production registry and Spring's start-up. A typo is a
    // failed start, not a runtime surprise.
    @Test
    void application_startsWithADeviceRequiredAndFailsOnATypo() {

        // Boot's conversion service, which the application has and a bare runner does not: it is what turns an empty
        // property into an empty list instead of a list holding one empty string.
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
                .withUserConfiguration(ParticipantAcksConfig.class, RequiredParticipantsValidator.class);

        runner.withPropertyValues("app.user.deletion.required-participants=devices")
                .run(context -> assertThat(context).hasNotFailed());

        runner.withPropertyValues("app.user.deletion.required-participants=")
                .run(context -> assertThat(context).hasNotFailed());

        runner.withPropertyValues("app.user.deletion.required-participants=devicez")
                .run(context -> assertThat(context).hasFailed());

    }
}
