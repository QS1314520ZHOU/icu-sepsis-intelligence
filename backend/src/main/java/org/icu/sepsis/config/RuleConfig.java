package org.icu.sepsis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.rules")
public class RuleConfig {
  private String version, approval;
  private double mapLow = 65, sbpLow = 90, lactateHigh = 2;
  private int suppressionMinutes = 30;

  public int getSuppressionMinutes() {
    return suppressionMinutes;
  }

  public void setSuppressionMinutes(int v) {
    if (v < 1) throw new IllegalArgumentException("Suppression minutes must be positive");
    suppressionMinutes = v;
  }

  private int freshMinutes = 120, labFreshMinutes = 360, reviewDeadlineMinutes = 60;

  public String getVersion() {
    return version;
  }

  public void setVersion(String v) {
    version = v;
  }

  public String getApproval() {
    return approval;
  }

  public void setApproval(String v) {
    approval = v;
  }

  public double getMapLow() {
    return mapLow;
  }

  public void setMapLow(double v) {
    mapLow = v;
  }

  public double getSbpLow() {
    return sbpLow;
  }

  public void setSbpLow(double v) {
    sbpLow = v;
  }

  public double getLactateHigh() {
    return lactateHigh;
  }

  public void setLactateHigh(double v) {
    lactateHigh = v;
  }

  public int getFreshMinutes() {
    return freshMinutes;
  }

  public void setFreshMinutes(int v) {
    freshMinutes = v;
  }

  public int getLabFreshMinutes() {
    return labFreshMinutes;
  }

  public void setLabFreshMinutes(int v) {
    labFreshMinutes = v;
  }

  public int getReviewDeadlineMinutes() {
    return reviewDeadlineMinutes;
  }

  public void setReviewDeadlineMinutes(int v) {
    reviewDeadlineMinutes = v;
  }
}
