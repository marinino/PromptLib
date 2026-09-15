package de.marinic.promptlib.user;

import java.util.Locale;

/**
 * The one place an email address gets normalized. Registration stores this form and login looks
 * the user up by it, so both MUST go through the same method - if only one side ever changed,
 * existing users would silently stop being able to log in.
 */
public final class EmailAddresses {

    private EmailAddresses() {}

    /**
     * Locale.ROOT, not the JVM default: under a Turkish default locale, "I".toLowerCase() is the
     * dotless "ı", so "INFO@firma.de" would be stored differently depending on which machine
     * handled the registration - and no longer be found after moving to another one.
     */
    public static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
