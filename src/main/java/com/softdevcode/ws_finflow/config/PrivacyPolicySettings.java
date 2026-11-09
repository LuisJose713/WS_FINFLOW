package com.softdevcode.ws_finflow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * This configuration cannot publish a privacy notice without the real data
 * controller identity/contact AND an explicit human legal-approval flag.
 * Empty values are never silently populated with fictitious information.
 */
@Component
public class PrivacyPolicySettings {
    public static final String VERSION = "2026-11-09-v1";
    public static final String EFFECTIVE_DATE = "2026-11-09";

    @Value("${FINFLOW_PRIVACY_PUBLISHED:false}") private boolean published;
    @Value("${FINFLOW_PRIVACY_CONTROLLER_NAME:}") private String controllerName;
    @Value("${FINFLOW_PRIVACY_CONTROLLER_NIT:}") private String controllerNit;
    @Value("${FINFLOW_PRIVACY_CONTACT_EMAIL:}") private String contactEmail;
    @Value("${FINFLOW_PRIVACY_CONTACT_ADDRESS:}") private String contactAddress;
    @Value("${FINFLOW_PRIVACY_CONTACT_PHONE:}") private String contactPhone;

    public boolean ready() {
        return published
            && controllerName != null && !controllerName.isBlank()
            && contactEmail != null && contactEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
            && contactAddress != null && !contactAddress.isBlank()
            && contactPhone != null && !contactPhone.isBlank();
    }

    public String controllerName() { return controllerName == null ? "" : controllerName; }
    public String controllerNit() { return controllerNit == null ? "" : controllerNit; }
    public String contactEmail() { return contactEmail == null ? "" : contactEmail; }
    public String contactAddress() { return contactAddress == null ? "" : contactAddress; }
    public String contactPhone() { return contactPhone == null ? "" : contactPhone; }
}
