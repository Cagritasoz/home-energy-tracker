package com.cagritasoz.user_service.testsupport.config;

import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// How the tests of a settings record check it: the record is bound in a real application context, from the real
// application.yaml with one property changed, which is what the start-up does. A refused value is a context that fails
// to start, and the failure names the property and the rule.
public final class ConfigAssertions {

    private ConfigAssertions() {
    }

    // A context that loads application.yaml (and the test resources' application.properties) the way the application
    // does and binds `properties`, a class annotated @ConfigurationProperties, and nothing else.
    public static ApplicationContextRunner runnerFor(Class<?> enablingConfiguration) {

        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(enablingConfiguration);

    }

    // `setting` is `key=value`. The start-up must fail, and the failure must name the field and say `reason`. Boot names
    // a rejected field the way the record spells it (`batchSize`), not the way the key is spelled (`batch-size`).
    //
    // `reason` is the constraint's name (`Positive`, `NotBlank`) or the text of a message the record sets itself. The
    // default message of a constraint follows the JVM's locale (Turkish on some machines), the constraint's name does not.
    public static void assertRefused(ApplicationContextRunner runner, String setting, String reason) {

        String key = setting.substring(0, setting.indexOf('='));
        String field = camelCase(key.substring(key.lastIndexOf('.') + 1));

        runner.withPropertyValues(setting).run(context -> {

            assertThat(context).as("start-up with %s", setting).hasFailed();
            assertThat(failureMessages(context.getStartupFailure()))
                    .contains(field)
                    .contains(reason);

        });
    }

    // A key under the prefix that no field matches (a typo): the failure names the whole key.
    public static void assertUnknownKeyRefused(ApplicationContextRunner runner, String setting) {

        String key = setting.substring(0, setting.indexOf('='));

        runner.withPropertyValues(setting).run(context -> {

            assertThat(context).as("start-up with %s", setting).hasFailed();
            assertThat(failureMessages(context.getStartupFailure())).contains(key);

        });
    }

    public static void assertAccepted(ApplicationContextRunner runner, String... settings) {

        runner.withPropertyValues(settings).run(context -> assertThat(context).as("start-up with %s", String.join(", ", settings)).hasNotFailed());

    }

    private static String camelCase(String kebab) {

        StringBuilder camel = new StringBuilder();
        boolean upper = false;

        for (char c : kebab.toCharArray()) {

            if (c == '-') {

                upper = true;

            }
            else {

                camel.append(upper ? Character.toUpperCase(c) : c);
                upper = false;

            }
        }

        return camel.toString();

    }

    // Everything the failure says, its causes included: Boot reports a rejected binding a few exceptions down.
    private static String failureMessages(Throwable failure) {

        StringBuilder messages = new StringBuilder();

        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {

            messages.append(cause.getMessage()).append('\n');

        }

        return messages.toString();

    }
}
