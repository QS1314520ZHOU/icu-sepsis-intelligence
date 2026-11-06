package org.icu.sepsis.quality;
import static org.junit.jupiter.api.Assertions.*;import java.util.*;import org.junit.jupiter.api.Test;
class QualityReportTest {
 @Test void reportCannotIncludePatientRowsOrConvertUnknownToFailure(){Map<String,Object> board=new HashMap<>();board.put("cases",List.of(Map.of("label","PRIVATE PATIENT")));board.put("windows",List.of(Map.of("key","1h","met",0,"notMet",0,"unknown",3,"inProgress",1)));String text=QualityReportController.render(board).get("text").toString();assertFalse(text.contains("PRIVATE PATIENT"));assertTrue(text.contains("达标率 无法判定"));assertTrue(text.contains("无法判定 3"));assertTrue(text.contains("乳酸取值窗口待确认"));}
}
