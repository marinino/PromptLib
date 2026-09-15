package de.marinic.promptlib.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class EmailAddressesTest {

    @Test
    void trimsAndLowercases() {
        assertThat(EmailAddresses.normalize("  Noah@Example.DE ")).isEqualTo("noah@example.de");
    }

    // Regression test: plain toLowerCase() uses the JVM default locale, under which Turkish
    // turns "I" into the dotless "ı" - the same address would be stored differently depending
    // on the server's locale.
    @Test
    void isIndependentOfTheDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(EmailAddresses.normalize("INFO@FIRMA.DE")).isEqualTo("info@firma.de");
        } finally {
            Locale.setDefault(original);
        }
    }
}
