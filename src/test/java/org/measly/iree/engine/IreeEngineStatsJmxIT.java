package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IreeEngineStatsJmxIT {

    @AfterEach
    void unregister() {
        IreeEngineStats.unregisterMBean();
    }

    @Test
    void registersAndReadsBackThroughThePlatformServer() throws Exception {
        IreeEngineStats.registerMBean();

        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(IreeEngineStats.OBJECT_NAME);
        assertTrue(server.isRegistered(name));

        // Reading through the server exercises the MXBean CompositeData conversion, which is
        // what would break if the interface lost its MXBean suffix or a value type gained a
        // setter.
        Object snapshot = server.getAttribute(name, "Snapshot");
        assertNotNull(snapshot);
        assertTrue(
                snapshot instanceof javax.management.openmbean.CompositeData,
                "an MXBean must convert the snapshot to CompositeData, got: "
                        + snapshot.getClass());

        javax.management.openmbean.CompositeData data =
                (javax.management.openmbean.CompositeData) snapshot;
        assertEquals(IreeEngine.getEngineVersion(), data.get("engineVersion"));
        assertNotNull(data.get("models"));
    }

    @Test
    void repeatedRegistrationDoesNotThrow() {
        IreeEngineStats.registerMBean();
        IreeEngineStats.registerMBean();
        assertEquals("REGISTERED", IreeEngineStats.snapshot().getJmxStatus());
    }

    @Test
    void statusIsReportedInTheSnapshot() {
        IreeEngineStats.registerMBean();
        IreeStatsSnapshot snapshot = IreeEngineStats.snapshot();
        assertEquals("REGISTERED", snapshot.getJmxStatus());
        assertEquals("", snapshot.getJmxError());
    }

    @Test
    void optOutPropertySuppressesAutoRegistration() {
        String previous = System.getProperty(IreeEngineStats.JMX_ENABLED_PROPERTY);
        System.setProperty(IreeEngineStats.JMX_ENABLED_PROPERTY, "false");
        try {
            IreeEngineStats.unregisterMBean();
            // registerMBeanOnce is one-shot per JVM, so assert the property read directly:
            // an explicit registerMBean() still works, which is the documented escape hatch.
            assertEquals("DISABLED", IreeEngineStats.snapshot().getJmxStatus());
        } finally {
            if (previous == null) {
                System.clearProperty(IreeEngineStats.JMX_ENABLED_PROPERTY);
            } else {
                System.setProperty(IreeEngineStats.JMX_ENABLED_PROPERTY, previous);
            }
        }
    }
}
