package org.measly.iree.engine;

import ai.djl.ndarray.types.DataType;

/**
 * Maps DJL data types to iree_hal_element_type_t values.
 *
 * <p>Only the types the skeleton exercises are mapped. Values are computed by
 * IREE's IREE_HAL_ELEMENT_TYPE_VALUE macro; verify against
 * iree/hal/buffer_view.h before adding more.
 */
public final class IreeDataTypes {

    // Verified against iree/hal/buffer_view.h:
    // IREE_HAL_ELEMENT_TYPE_VALUE(numerical_type, bit_count) =
    //     (numerical_type << 24) | bit_count.
    // FLOAT_32: numerical_type = FLOAT_IEEE (0x21), bit_count = 32 (0x20).
    // SINT_32:  numerical_type = INTEGER_SIGNED (0x11), bit_count = 32 (0x20).
    public static final int FLOAT_32 = 0x21000020;
    public static final int SINT_32 = 0x11000020;

    private IreeDataTypes() {}

    public static int toIree(DataType type) {
        switch (type) {
            case FLOAT32:
                return FLOAT_32;
            case INT32:
                return SINT_32;
            default:
                throw new UnsupportedOperationException(
                        "Unsupported data type for the IREE skeleton: " + type);
        }
    }

    public static DataType fromIree(int elementType) {
        switch (elementType) {
            case FLOAT_32:
                return DataType.FLOAT32;
            case SINT_32:
                return DataType.INT32;
            default:
                throw new UnsupportedOperationException(
                        "Unsupported IREE element type: 0x"
                                + Integer.toHexString(elementType));
        }
    }
}
