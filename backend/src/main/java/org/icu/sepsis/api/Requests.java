package org.icu.sepsis.api;

import java.time.Instant;
import javax.validation.constraints.*;

public final class Requests {
  public static class Assess {
    @NotNull public Instant asOf;
  }

  public static class Review {
    @NotBlank
    @Size(max = 40)
    public String action;

    @NotBlank
    @Size(max = 1000)
    public String reason;

    @NotNull
    @Min(0)
    public Long expectedVersion;
  }

  public static class TaskReview extends Review {
    @NotBlank public String applicability;
    @NotBlank public String targetStatus;
  }

  public static class Execute {
    @NotNull public Instant eventTime;

    @NotBlank
    @Size(max = 128)
    public String sourceRecordId;

    @NotBlank
    @Size(max = 80)
    public String sourceSystem;

    @NotBlank
    @Size(max = 1000)
    public String reason;

    @NotNull
    @Min(0)
    public Long expectedVersion;
  }
}
