package brooklyn.web.console.test

import org.testng.annotations.Test
import static org.testng.Assert.*;

public class StatusTest extends AbstractSeleniumTest {

    @Test public void statusIndicator() {
        selenium.open("/entity/");

        selenium.isTextPresent("No data yet.")

        // Wait for tree to load
        waitFor({selenium.isTextPresent("tomcat")});

        selenium.click("link=tomcat node 1a.3")

        waitFor({selenium.isTextPresent("regexp:\\d{2}:\\d{2}:\\d{2}")});
    }
}