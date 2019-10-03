package reactor.blockhound.junit.platform;

import com.google.auto.service.AutoService;
import org.junit.platform.launcher.TestExecutionListener;
import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * This {@link TestExecutionListener} installs BlockHound (via {@link BlockHound#install(BlockHoundIntegration...)}
 * as soon as JUnit Platform loads it.
 *
 * Although the class is public, it is only so due to SPI's limitation and SHOULD NOT be considered a public API.
 */
@AutoService(TestExecutionListener.class)
public class BlockHoundTestExecutionListener implements TestExecutionListener {

    static {
        // Install it as early as possible (when JUnit Runner loads this class)
        BlockHound.install();
    }
}
