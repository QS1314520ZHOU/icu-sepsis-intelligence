package org.icu.sepsis.deterioration;

import java.time.Instant;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import org.icu.sepsis.api.ApiException;
import org.icu.sepsis.api.Envelope;
import org.icu.sepsis.api.Views;
import org.icu.sepsis.application.Store;
import org.icu.sepsis.domain.Domain.Patient;
import org.icu.sepsis.embed.EmbedAccess;
import org.icu.sepsis.integration.SmartCareSourceReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only department batch screening. The department comes from the authenticated grant/config, never a request parameter. */
@RestController
@RequestMapping("/api/v1/department-screening")
@PreAuthorize("hasAnyRole('DOCTOR','QUALITY')")
public class DepartmentScreeningController {
  private final Store store;
  private final DeteriorationService deterioration;
  private final ObjectProvider<SmartCareSourceReader> source;

  public DepartmentScreeningController(Store store, DeteriorationService deterioration,
      ObjectProvider<SmartCareSourceReader> source) {
    this.store = store;
    this.deterioration = deterioration;
    this.source = source;
  }

  @GetMapping("/patients")
  public Envelope<?> patients(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
      @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size) {
    String dept = authorizedDepartment();
    SmartCareSourceReader reader = requireSource();
    SmartCareSourceReader.PatientPage roster = reader.listByDept(dept, page, size, "");
    return Envelope.ok(Views.map("items", roster.items, "total", roster.total,
        "page", page, "size", size, "department", dept, "source", "SmartCare"));
  }

  @GetMapping("/patients/{id}/deterioration")
  public Envelope<?> patientRisk(@PathVariable String id) {
    String dept = authorizedDepartment();
    SmartCareSourceReader reader = requireSource();
    Patient patient = reader.loadPatientForDept(id, dept);
    return Envelope.ok(deterioration.read(patient, Instant.now()));
  }

  @GetMapping("/icu08-official")
  public Envelope<?> icu08Official(@RequestParam String period) {
    String dept = authorizedDepartment();
    return Envelope.ok(requireSource().icu08OfficialAudit(dept, period));
  }

  @GetMapping("/icu08-recompute")
  public Envelope<?> icu08Recompute(@RequestParam String period) {
    String dept = authorizedDepartment();
    return Envelope.ok(requireSource().icu08MonthlyRecompute(dept, period));
  }

  private SmartCareSourceReader requireSource() {
    SmartCareSourceReader reader = source.getIfAvailable();
    if (reader == null || !reader.isReadable())
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_UNAVAILABLE", "科室患者来源暂不可用");
    return reader;
  }

  private String authorizedDepartment() {
    EmbedAccess.Grant grant = EmbedAccess.current();
    if (grant != null && grant.patientId != null && !grant.departmentReadAllowed)
      throw new ApiException(HttpStatus.FORBIDDEN, "DEPARTMENT_SCOPE_DENIED", "当前嵌入授权仅限当前患者");
    String dept = grant != null ? grant.department : store.department;
    if (dept == null || dept.isBlank())
      throw new ApiException(HttpStatus.FORBIDDEN, "DEPARTMENT_SCOPE_MISSING", "当前授权没有科室范围");
    return dept;
  }
}
