package org.measly.iree.engine;

/**
 * JMX view of {@link IreeEngineStats}, registered as {@value IreeEngineStats#OBJECT_NAME}.
 *
 * <p>An <b>MX</b>Bean rather than a plain MBean: the JMX runtime converts {@link
 * IreeStatsSnapshot} and its nested {@code List<IreeModelStats>} to {@code CompositeData}/{@code
 * TabularData} automatically, so no hand-written {@code OpenType} mapping is needed.
 *
 * <p><b>The {@code MXBean} suffix on this interface's name is load-bearing.</b> There is no
 * annotation — the suffix is the whole declaration. Renaming this interface silently downgrades
 * it to a Standard MBean, at which point the {@code List} conversion stops applying and
 * registration fails with {@code NotCompliantMBeanException}. Keeping that conversion working is
 * also why both value types are getter-only JavaBeans.
 */
public interface IreeEngineStatsMXBean {

    /** @return a fresh snapshot of engine configuration, totals, and live models */
    IreeStatsSnapshot getSnapshot();
}
