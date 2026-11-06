package org.icu.sepsis.ai;
import java.util.*;
import java.util.regex.*;
/** Conservative citation checks for literal numbers and named measurements, not a medical correctness proof. */
final class CitationEvidenceGuard {
  private static final Pattern NUMBER=Pattern.compile("(?<![A-Za-z])\\d+(?:\\.\\d+)?");
  private static final Pattern MEASURE=Pattern.compile("(?i)(MAP|平均动脉压|乳酸|心率|呼吸频率|GCS|体温|SpO2|SpO₂)\\s*(?:[（(][^）)]{0,12}[）)])?\\s*[:：]?\\s*(?:[<>≤≥=]\\s*)?(\\d+(?:\\.\\d+)?)\\s*(mmHg|kPa|mmol/L|mg/dL|次/分|bpm|℃|°C|%)?");
  private static String metric(String s){switch(s.toLowerCase(Locale.ROOT)){case "map":case "平均动脉压":return "map";case "spo₂":case "spo2":return "spo2";default:return s.toLowerCase(Locale.ROOT);}}
  private static String number(String s){return new java.math.BigDecimal(s).stripTrailingZeros().toPlainString();}
  private static String unit(String s){return s==null?"":s.toLowerCase(Locale.ROOT).replace("°c","℃").replace("bpm","次/分");}
  private static String withoutReferences(String s){return s.replaceAll("[EKP]\\d{1,3}"," ");}
  private static boolean trendSupports(String source,String subject,String value,String expectedUnit) {
    Matcher metric=Pattern.compile("(?i)^(MAP|平均动脉压|乳酸|心率|呼吸频率|GCS|体温|SpO2|SpO₂)(?:[（(][^）)]{0,12}[）)])?\\s+近 24h ").matcher(source);
    if(!metric.find() || !subject.equals(metric(metric.group(1))))return false;
    Matcher recent=Pattern.compile("最近\\s*\\d+(?:\\.\\d+)?\\s*(mmHg|kPa|mmol/L|mg/dL|次/分|bpm|℃|°C|%)?",Pattern.CASE_INSENSITIVE).matcher(source);
    if(!recent.find() || !expectedUnit.isEmpty() && !expectedUnit.equals(unit(recent.group(1))))return false;
    Matcher point=Pattern.compile("(?:首次|最近|最低|最高|均值)\\s*(\\d+(?:\\.\\d+)?)").matcher(source);
    while(point.find())if(value.equals(number(point.group(1))))return true;
    return false;
  }

  private static boolean supports(List<String> cited,String subject,String value,String expectedUnit) {
    for(String source:cited){if(trendSupports(source,subject,value,expectedUnit))return true;
      Matcher fact=MEASURE.matcher(source);while(fact.find())if(subject.equals(metric(fact.group(1)))&&value.equals(number(fact.group(2)))
          &&(expectedUnit.isEmpty()||expectedUnit.equals(unit(fact.group(3)))))return true;}
    return false;
  }

  static String check(String text,List<String> cited){
    Set<String> known=new HashSet<>();for(String source:cited){Matcher m=NUMBER.matcher(withoutReferences(source));while(m.find())known.add(number(m.group()));}
    Matcher numeric=NUMBER.matcher(withoutReferences(text));while(numeric.find())if(!known.contains(number(numeric.group())))return "数值 "+numeric.group()+" 未获本条引用支持";
    Matcher claim=MEASURE.matcher(text);
    while(claim.find()){
      String subject=metric(claim.group(1)),value=number(claim.group(2)),expectedUnit=unit(claim.group(3));boolean matched=false;
      matched=supports(cited,subject,value,expectedUnit);
      if(!matched)return "指标、数值或单位未获本条引用中同一测量支持";
    }
    Matcher trendClaim=Pattern.compile("(?i)(MAP|平均动脉压|乳酸|心率|呼吸频率|GCS|体温|SpO2|SpO₂)\\s*(?:由|从)?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:上升至|升至|下降至|降至|→|至)\\s*(\\d+(?:\\.\\d+)?)\\s*(mmHg|kPa|mmol/L|mg/dL|次/分|bpm|℃|°C|%)?").matcher(text);
    while(trendClaim.find())for(int group:new int[]{2,3})if(!supports(cited,metric(trendClaim.group(1)),number(trendClaim.group(group)),unit(trendClaim.group(4))))return "趋势端点未获同一指标引用支持";
    Matcher ordered=Pattern.compile("(?i)(MAP|平均动脉压|乳酸|心率|呼吸频率|GCS|体温|SpO2|SpO₂)\\s*(?:由|从)?\\s*(\\d+(?:\\.\\d+)?)\\s*(上升至|升至|下降至|降至|→|至)\\s*(\\d+(?:\\.\\d+)?)").matcher(text);
    while(ordered.find()){
      double first=Double.parseDouble(ordered.group(2)),last=Double.parseDouble(ordered.group(4));String direction=ordered.group(3);
      if((direction.contains("升")&&last<=first)||(direction.contains("降")&&last>=first))return "趋势方向与端点不一致";
      boolean sequence=false;
      for(String source:cited){Matcher subject=Pattern.compile("(?i)^(MAP|平均动脉压|乳酸|心率|呼吸频率|GCS|体温|SpO2|SpO₂)").matcher(source);if(!subject.find()||!metric(subject.group(1)).equals(metric(ordered.group(1))))continue;
        Matcher endpoints=Pattern.compile("首次\\s*(\\d+(?:\\.\\d+)?).*?最近\\s*(\\d+(?:\\.\\d+)?)").matcher(source);
        if(endpoints.find()&&number(endpoints.group(1)).equals(number(ordered.group(2)))&&number(endpoints.group(2)).equals(number(ordered.group(4))))sequence=true;
      }
      if(!sequence)return "趋势先后顺序未获同一指标时间序列支持";
    }
    return null;
  }
}
