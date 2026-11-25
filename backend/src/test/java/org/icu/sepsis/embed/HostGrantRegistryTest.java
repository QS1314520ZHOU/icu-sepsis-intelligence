package org.icu.sepsis.embed;

import static org.junit.jupiter.api.Assertions.*;

import org.icu.sepsis.domain.Domain.Patient;
import org.junit.jupiter.api.Test;

class HostGrantRegistryTest {
  @Test void departmentReadRequiresDoctorOrQualityRole() {
    HostGrantRegistry registry = new HostGrantRegistry();
    Patient p = new Patient(); p.id = "0123456789abcdef01234567"; p.encounterId = "stay-1"; p.department = "3439";
    var doctor = registry.issue("https://host.example", p, "doctor");
    var doctorGrant = registry.lookup(String.valueOf(doctor.get("accessToken")));
    assertTrue(doctorGrant.departmentReadAllowed);
    assertTrue(doctorGrant.authorities.stream().anyMatch(a -> "ROLE_DOCTOR".equals(a.getAuthority())));

    var nurse = registry.issue("https://host.example", p, "nurse");
    var nurseGrant = registry.lookup(String.valueOf(nurse.get("accessToken")));
    assertFalse(nurseGrant.departmentReadAllowed);
    assertTrue(nurseGrant.authorities.stream().anyMatch(a -> "ROLE_NURSE".equals(a.getAuthority())));

    var unknown = registry.issue("https://host.example", p, null);
    var unknownGrant = registry.lookup(String.valueOf(unknown.get("accessToken")));
    assertFalse(unknownGrant.departmentReadAllowed);
  }
}
