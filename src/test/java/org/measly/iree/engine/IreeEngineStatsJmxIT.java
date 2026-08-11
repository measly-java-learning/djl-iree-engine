package org.measly.iree.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * Drives the real auto-registration path in both property states.
     *
     * <p>The previous version of this test asserted {@code DISABLED} after calling {@code
     * unregisterMBean()}, which sets {@code DISABLED} itself — so it passed with the property set
     * to {@code "true"}, with the property cleared, and with the opt-out branch deleted outright.
     * It asserted nothing about the only supported way to turn JMX off.
     */
    @Test
    void optOutPropertySuppressesAutoRegistration() throws Exception {
        String previous = System.getProperty(IreeEngineStats.JMX_ENABLED_PROPERTY);
        MBeanServer server = ManagementFactory.getPlatformMBeanServer();
        ObjectName name = new ObjectName(IreeEngineStats.OBJECT_NAME);
        try {
            IreeEngineStats.unregisterMBean();

            System.setProperty(IreeEngineStats.JMX_ENABLED_PROPERTY, "false");
            assertFalse(IreeEngineStats.jmxEnabled());
            IreeEngineStats.resetJmxAutoRegistrationForTesting();
            IreeEngineStats.registerMBeanOnce();
            assertFalse(server.isRegistered(name), "opt-out must suppress auto-registration");
            assertEquals("DISABLED", IreeEngineStats.snapshot().getJmxStatus());

            // The same call with the property absent must register — otherwise the assertion
            // above would hold for a build where auto-registration never worked at all.
            System.clearProperty(IreeEngineStats.JMX_ENABLED_PROPERTY);
            assertTrue(IreeEngineStats.jmxEnabled());
            IreeEngineStats.resetJmxAutoRegistrationForTesting();
            IreeEngineStats.registerMBeanOnce();
            assertTrue(server.isRegistered(name), "absent property means opt-in");
            assertEquals("REGISTERED", IreeEngineStats.snapshot().getJmxStatus());

            // And it really is one-shot: after a manual unregister, a second auto-attempt is
            // not made.
            IreeEngineStats.unregisterMBean();
            IreeEngineStats.registerMBeanOnce();
            assertFalse(
                    server.isRegistered(name), "auto-registration is attempted exactly once");
        } finally {
            IreeEngineStats.resetJmxAutoRegistrationForTesting();
            if (previous == null) {
                System.clearProperty(IreeEngineStats.JMX_ENABLED_PROPERTY);
            } else {
                System.setProperty(IreeEngineStats.JMX_ENABLED_PROPERTY, previous);
            }
        }
    }

    /** An explicit registerMBean() is the documented escape hatch and ignores the property. */
    @Test
    void explicitRegistrationIgnoresTheOptOutProperty() throws Exception {
        String previous = System.getProperty(IreeEngineStats.JMX_ENABLED_PROPERTY);
        System.setProperty(IreeEngineStats.JMX_ENABLED_PROPERTY, "false");
        try {
            IreeEngineStats.registerMBean();
            assertTrue(
                    ManagementFactory.getPlatformMBeanServer()
                            .isRegistered(new ObjectName(IreeEngineStats.OBJECT_NAME)));
            assertEquals("REGISTERED", IreeEngineStats.snapshot().getJmxStatus());
        } finally {
            if (previous == null) {
                System.clearProperty(IreeEngineStats.JMX_ENABLED_PROPERTY);
            } else {
                System.setProperty(IreeEngineStats.JMX_ENABLED_PROPERTY, previous);
            }
        }
    }
}
