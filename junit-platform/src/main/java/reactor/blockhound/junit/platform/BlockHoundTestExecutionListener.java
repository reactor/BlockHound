package reactor.blockhound.junit.platform;

import com.google.auto.service.AutoService;
import org.junit.platform.launcher.TestExecutionListener;
import reactor.blockhound.BlockHound;

@AutoService(TestExecutionListener.class)
public class BlockHoundTestExecutionListener implements TestExecutionListener {

    static {
        // Install it as early as possible (when JUnit Runner loads this class)
        BlockHound.install();
    }
}
