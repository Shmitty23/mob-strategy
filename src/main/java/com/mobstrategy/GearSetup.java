package com.mobstrategy;

import java.util.ArrayList;
import java.util.List;

/** One recommended gear table, e.g. the "Melee" setup. */
class GearSetup
{
	/** Name of the table, e.g. "Melee" or "Ranged". */
	String style;

	/** Each equipment slot that has recommendations, in equipment-screen order. */
	List<GearSlot> slots = new ArrayList<>();
}
