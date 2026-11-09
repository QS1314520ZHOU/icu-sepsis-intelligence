package org.icu.sepsis.sepsis;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component
@ConfigurationProperties(prefix="app.screening.mews")
public class MewsProperties {
 private boolean enabled=true; private int freshMinutes=30,temperatureFreshMinutes=240,maxSkewMinutes=15,reviewThreshold=5;
 public boolean isEnabled(){return enabled;}public void setEnabled(boolean v){enabled=v;}
 public int getFreshMinutes(){return freshMinutes;}public void setFreshMinutes(int v){if(v<1||v>120)throw new IllegalArgumentException("MEWS freshness must be 1..120 minutes");freshMinutes=v;}
 public int getTemperatureFreshMinutes(){return temperatureFreshMinutes;}public void setTemperatureFreshMinutes(int v){if(v<1||v>1440)throw new IllegalArgumentException("MEWS temperature freshness must be 1..1440 minutes");temperatureFreshMinutes=v;}
 public int getMaxSkewMinutes(){return maxSkewMinutes;}public void setMaxSkewMinutes(int v){if(v<0||v>120)throw new IllegalArgumentException("MEWS skew must be 0..120 minutes");maxSkewMinutes=v;}
 public int getReviewThreshold(){return reviewThreshold;}public void setReviewThreshold(int v){if(v<1||v>14)throw new IllegalArgumentException("MEWS threshold must be 1..14");reviewThreshold=v;}
}
