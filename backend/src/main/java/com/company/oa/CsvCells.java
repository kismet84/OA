package com.company.oa;

/** Quote every cell and neutralize spreadsheet formula prefixes, including fullwidth variants. */
final class CsvCells {
  private CsvCells() {}

  static String cell(Object value) {
    String s = value == null ? "" : value.toString();
    String leading = s.stripLeading();
    if (s.startsWith("\t") || s.startsWith("\r") || s.startsWith("\n")
        || (!leading.isEmpty() && "=+-@＝＋－＠".indexOf(leading.charAt(0)) >= 0))
      s = "'" + s;
    return "\"" + s.replace("\"", "\"\"") + "\"";
  }
}
