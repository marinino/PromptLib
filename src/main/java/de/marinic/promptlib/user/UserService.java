package de.marinic.promptlib.user;

import de.marinic.promptlib.common.error.ConflictException;
import de.marinic.promptlib.user.dto.RegisterRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public User register(RegisterRequest request) {
        String normalizedEmail = EmailAddresses.normalize(request.email());
        // Fast path for the common case - but not sufficient on its own: two concurrent
        // registrations with the same email can both pass this check before either inserts.
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw emailTaken(normalizedEmail);
        }

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        // The UNIQUE constraint on app_user.email is what actually decides the race. saveAndFlush,
        // not save: a plain save() would defer the INSERT to commit time, i.e. after this method
        // has returned, where the violation could no longer be caught here and would surface as
        // a 500. Catching it doesn't try to "recover" the transaction (it's rollback-only by
        // now) - it only replaces the exception, and the rollback that follows is exactly right.
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken(normalizedEmail);
        }
    }

    // This does reveal whether an address is registered, unlike login's deliberately uniform
    // 401. Accepted on purpose: truly hiding it needs a confirmation-email flow that always
    // answers the same way, which this project doesn't have - and without one, a real user with
    // an existing account would never learn why registering did nothing.
    private static ConflictException emailTaken(String normalizedEmail) {
        return new ConflictException("Email %s is already registered".formatted(normalizedEmail));
    }
}
