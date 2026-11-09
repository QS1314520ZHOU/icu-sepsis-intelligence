package org.icu.sepsis.ai;import org.junit.jupiter.api.Test;import static org.junit.jupiter.api.Assertions.*;
class ClinicalTextTest {@Test void internalCodesAreChineseWhileMedicalAcronymsAndIdentifiersStayIntact(){assertEquals("MEWS 意识无法准确映射；有效；无法判定，qSOFA AVPU unmapped_field E14",ClinicalText.localize("MEWS 意识unmapped；ready；unknown，qSOFA AVPU unmapped_field E14"));}}
