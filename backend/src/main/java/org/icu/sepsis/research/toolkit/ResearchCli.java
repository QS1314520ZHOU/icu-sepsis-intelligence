package org.icu.sepsis.research.toolkit;

import java.nio.file.Path;
import java.util.*;

/**
 * Offline research toolkit commands (no Spring context, no hospital database):
 * <pre>
 *   java -jar sepsis-intelligence.jar research-report --export DIR --out DIR
 *   java -jar sepsis-intelligence.jar research-synth --out DIR [--n 1200 --seed 1 --scenario signal|null]
 *   java -jar sepsis-intelligence.jar research-e2e-seed --salt S --truth FILE [--uri mongodb://127.0.0.1:27117 --n 500 --seed 5]
 *   java -jar sepsis-intelligence.jar research-e2e-verify EXPORT_DIR TRUTH_JSON REPORT_DIR
 * </pre>
 */
public final class ResearchCli {
  public static final Set<String> COMMANDS = Set.of("research-report", "research-synth", "research-e2e-seed", "research-e2e-verify");

  private ResearchCli() {}

  public static boolean handles(String[] args) { return args.length > 0 && COMMANDS.contains(args[0]); }

  static Map<String, String> options(String[] args) {
    Map<String, String> o = new HashMap<>();
    for (int i = 1; i < args.length; i++) {
      if (!args[i].startsWith("--")) continue;
      String k = args[i].substring(2);
      int eq = k.indexOf('=');
      if (eq > 0) o.put(k.substring(0, eq), k.substring(eq + 1));
      else if (i + 1 < args.length) o.put(k, args[++i]);
    }
    return o;
  }

  static void quietLogs() {
    try {
      org.slf4j.Logger root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
      if (root instanceof ch.qos.logback.classic.Logger) ((ch.qos.logback.classic.Logger) root).setLevel(ch.qos.logback.classic.Level.WARN);
    } catch (Throwable ignored) { // logging backend absent: nothing to quiet
    }
  }

  public static int run(String[] args) {
    quietLogs();
    Map<String, String> o = options(args);
    try {
      switch (args[0]) {
        case "research-synth": {
          String out = o.get("out");
          if (out == null) return usage();
          String scenario = o.getOrDefault("scenario", "signal");
          if (!scenario.equals("signal") && !scenario.equals("null")) return usage();
          System.out.println(Synth.write(Path.of(out), Integer.parseInt(o.getOrDefault("n", "1200")), Long.parseLong(o.getOrDefault("seed", "1")), scenario));
          return 0;
        }
        case "research-report": {
          if (o.get("export") == null || o.get("out") == null) return usage();
          Map<String, Object> res = Report.run(Path.of(o.get("export")), Path.of(o.get("out")));
          Data.map(res.get("sections")).forEach((k, v) -> System.out.println(k + ": " + Data.map(v).get("status")));
          return Report.anyError(res) ? 1 : 0;
        }
        case "research-e2e-seed":
          return E2eSeed.main(o);
        case "research-e2e-verify":
          if (args.length < 4) return usage();
          return E2eVerify.main(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
        default:
          return usage();
      }
    } catch (Exception e) {
      System.err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
      return 2;
    }
  }

  static int usage() {
    System.err.println("用法：research-report --export DIR --out DIR | research-synth --out DIR [--n N --seed S --scenario signal|null]"
        + " | research-e2e-seed --salt S --truth FILE [--uri URI --n N --seed S] | research-e2e-verify EXPORT TRUTH REPORT");
    return 2;
  }
}
