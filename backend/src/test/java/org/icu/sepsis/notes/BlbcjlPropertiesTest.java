package org.icu.sepsis.notes;
import static org.junit.jupiter.api.Assertions.*;import org.junit.jupiter.api.Test;import org.springframework.boot.test.context.runner.ApplicationContextRunner;import org.springframework.context.annotation.*;
class BlbcjlPropertiesTest {
 @Configuration @Import(BlbcjlProperties.class) static class Config{}
 @Test void missingNewPropertyKeepsLegacyBodyCompatibilityEnabled(){new ApplicationContextRunner().withUserConfiguration(Config.class).run(context->assertTrue(context.getBean(BlbcjlProperties.class).allowLegacyBodyEscaping));}
 @Test void explicitDisableRemainsRespected(){new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues("app.clinical-notes.blbcjl.allow-legacy-body-escaping=false").run(context->assertFalse(context.getBean(BlbcjlProperties.class).allowLegacyBodyEscaping));}
}
