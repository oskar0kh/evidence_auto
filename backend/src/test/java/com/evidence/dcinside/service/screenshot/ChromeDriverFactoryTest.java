package com.evidence.dcinside.service.screenshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChromeDriverFactoryTest {

    @Test
    void detectChromeFullVersion_fromOutput() {
        assertEquals(
                "150.0.7871.46",
                ChromeDriverFactory.detectChromeFullVersion("Google Chrome 150.0.7871.46")
        );
    }
}
