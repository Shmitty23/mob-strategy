package com.mobstrategy;

import java.util.ArrayList;
import java.util.List;

/** The recommended options for one equipment slot, best first. */
class GearSlot
{
	/** Slot key, e.g. "head", "weapon", "2h", "special". */
	String key;

	/** Slot name shown in the panel, e.g. "Head" or "Weapon". */
	String slot;

	/** Options ranked from best (first) to worst (last). */
	List<GearOption> options = new ArrayList<>();
}
