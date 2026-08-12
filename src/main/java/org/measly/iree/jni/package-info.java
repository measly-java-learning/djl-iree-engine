/**
 * Internal JNI boundary to the {@code iree_djl} native shim. Not a supported API.
 *
 * <p><strong>This package is internal and unstable.</strong> It is public only because JNI
 * requires it. Its types, signatures, and native contract change in lockstep with the native
 * shim and are not covered by any compatibility guarantee — a patch release may change them.
 * Application code should use {@link org.measly.iree.engine} and DJL's own interfaces instead.
 *
 * <p>Every method in {@link org.measly.iree.jni.IreeNative} is a thin pass-through to native
 * code. The javadoc on each one states the contract the caller must satisfy: nothing here
 * validates its arguments beyond what the shim does, and violating a documented precondition
 * is undefined behavior in native code rather than a Java exception.
 */
package org.measly.iree.jni;
