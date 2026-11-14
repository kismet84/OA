package com.company.oa;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class CsvCellsTest {
  @Test void neutralizesEveryDangerousPrefix() {
    for (String s : new String[] {"=1+1", "+1", "-1", "@SUM", " =HYPERLINK(\"x\")",
        "\n=cmd", "\r+1", "\t@x", "  \n -2", "＝1", " ＋1", "－1", "＠SUM"}) {
      assertEquals("\"'" + s.replace("\"", "\"\"") + "\"", CsvCells.cell(s), s);
    }
  }
  @Test void quotesOrdinaryValuesAndNull() {
    assertEquals("\"普通中文\"", CsvCells.cell("普通中文"));
    assertEquals("\"含\"\"引号,文本\"", CsvCells.cell("含\"引号,文本"));
    assertEquals("\"\"", CsvCells.cell(null));
    assertEquals("\"\"", CsvCells.cell(""));
    assertEquals("\"123.45\"", CsvCells.cell(new java.math.BigDecimal("123.45")));
  }
}
