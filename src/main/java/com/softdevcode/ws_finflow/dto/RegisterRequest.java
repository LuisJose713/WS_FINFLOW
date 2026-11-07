package com.softdevcode.ws_finflow.dto;

public class RegisterRequest {

    private String name;
    private String email;
    private String password;
    private String confirmPassword;
    private Boolean privacyAccepted;
    private String privacyVersion;
    private Boolean marketingConsent;

    public Boolean getPrivacyAccepted() { return privacyAccepted; }
    public void setPrivacyAccepted(Boolean accepted) { this.privacyAccepted = accepted; }
    public String getPrivacyVersion() { return privacyVersion; }
    public void setPrivacyVersion(String version) { this.privacyVersion = version; }
    public Boolean getMarketingConsent() { return marketingConsent; }
    public void setMarketingConsent(Boolean consent) { this.marketingConsent = consent; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public String getConfirmPassword() { return confirmPassword; }
    public void setConfirmPassword(String confirmPassword) { this.confirmPassword = confirmPassword; }
    public void setPassword(String password) { this.password = password; }
}
