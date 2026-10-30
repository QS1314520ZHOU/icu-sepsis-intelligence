package org.icu.sepsis.domain;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Shared clause-level reading of free-text diagnoses. One implementation for the screening rules,
 * the clinical review and ICU-05, so that "排除肺炎" / "非感染性发热" / "无菌性脑膜炎" are never read as
 * a positive infection clue in one place and a negative in another.
 *
 * <p>Heuristic only: a text clue is never a confirmed infection.
 */
public final class InfectionText {
  private InfectionText() {}

  public enum Clause { NONE, POSITIVE, NEGATED, UNCERTAIN, HISTORY }

  /** "Cannot exclude" wording: the infection stays possible. Checked before plain negation. */
  static final Pattern UNCERTAIN =
      Pattern.compile(
          "不能排除|尚不能排除|难以排除|无法排除|不除外|待排|可能|待查|疑似|？|\\?|cannot.exclude|possible|suspected",
          Pattern.CASE_INSENSITIVE);
  static final Pattern HISTORY = Pattern.compile("^\\s*(既往|病史|history of)", Pattern.CASE_INSENSITIVE);
  /** Negation cue immediately before a keyword (same clause, at most 6 chars apart). */
  static final Pattern NEG_BEFORE =
      Pattern.compile("(排除|除外|否认|未见|未发现|没有|无|非|\\bnot|\\bno|\\bnon-?)\\s*[^，,。；;]{0,6}$", Pattern.CASE_INSENSITIVE);
  /** A clinical verb between the cue and the keyword ends the negation scope ("无诱因出现肺部感染"). */
  static final Pattern SCOPE_BREAK = Pattern.compile("出现|伴|合并|考虑|诊断|继发|并发|发生");
  /** Negation right after a keyword ("感染已排除", "肺炎不支持"). */
  static final Pattern NEG_AFTER = Pattern.compile("^[^，,。；;]{0,3}(已排除|已除外|除外|排除|不支持|可能性小|证据不足)");

  public static List<String> clauses(String text) {
    if (text == null || text.isBlank()) return List.of();
    return Arrays.asList(text.toLowerCase(Locale.ROOT).split("[，,。；;|\\n]"));
  }

  /** Classify one clause against a keyword list. */
  public static Clause classify(String clause, List<String> keywords) {
    if (clause == null || clause.isBlank()) return Clause.NONE;
    String c = clause.toLowerCase(Locale.ROOT);
    boolean hit = false, positive = false;
    for (String k : keywords) {
      String kw = k.toLowerCase(Locale.ROOT);
      for (int i = c.indexOf(kw); i >= 0; i = c.indexOf(kw, i + 1)) {
        hit = true;
        if (!negated(c, i, i + kw.length())) positive = true;
      }
    }
    if (!hit) return Clause.NONE;
    if (UNCERTAIN.matcher(c.replace("可能性小", "")).find()) return Clause.UNCERTAIN;
    if (HISTORY.matcher(c).find()) return Clause.HISTORY;
    return positive ? Clause.POSITIVE : Clause.NEGATED;
  }

  private static boolean negated(String c, int start, int end) {
    String before = c.substring(Math.max(0, start - 8), start);
    java.util.regex.Matcher m = NEG_BEFORE.matcher(before);
    if (m.find()) {
      String gap = before.substring(m.end(1));
      if (!SCOPE_BREAK.matcher(gap).find()) return true;
    }
    return NEG_AFTER.matcher(c.substring(end)).find();
  }

  /** True when any clause carries a positive or "cannot exclude" infection clue. */
  public static boolean hasClue(String text, List<String> keywords) {
    for (String clause : clauses(text)) {
      Clause k = classify(clause, keywords);
      if (k == Clause.POSITIVE || k == Clause.UNCERTAIN) return true;
    }
    return false;
  }
}
