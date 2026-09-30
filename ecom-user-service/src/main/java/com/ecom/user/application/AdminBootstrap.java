package com.ecom.user.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import com.ecom.platform.core.EcomException;

/** Creates the bootstrap admin from ADMIN_EMAIL / ADMIN_PASSWORD when both are set. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final RegistrationService registration;
    private final AuthProperties props;

    public AdminBootstrap(RegistrationService registration, AuthProperties props) {
        this.registration = registration;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.bootstrapAdminConfigured()) {
            return;
        }
        try {
            boolean created = registration.ensureAdmin(props.adminEmail(), props.adminPassword());
            log.info(created ? "Bootstrap admin account created" : "Bootstrap admin account already present");
        } catch (EcomException | DataIntegrityViolationException _) {
            // another replica created it concurrently; the next start converges the roles
            log.info("Bootstrap admin created concurrently by another instance");
        }
    }
}
