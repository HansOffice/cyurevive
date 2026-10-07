package org.cyu.cyurevive;

import net.minecraft.util.StringRepresentable;

public enum FacilityPart implements StringRepresentable {
    CELL_0, CELL_1, CELL_2, CELL_3, CELL_4, CELL_5, CELL_6, CELL_7, CELL_8, CELL_9, CELL_10, CELL_11, CELL_12, CELL_13, CELL_14, CELL_15, CELL_16, CELL_17, CELL_18, CELL_19, CELL_20, CELL_21, CELL_22, CELL_23, CELL_24, CELL_25, CELL_26, CELL_27, CELL_28, CELL_29, CELL_30, CELL_31, CELL_32, CELL_33, CELL_34, CELL_35;

    private static final FacilityPart[] CELLS = values();

    public static FacilityPart at(int index) { return CELLS[index]; }

    @Override
    public String getSerializedName() { return Integer.toString(ordinal()); }
}
