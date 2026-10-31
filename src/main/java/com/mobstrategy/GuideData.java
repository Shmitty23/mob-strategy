package com.mobstrategy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The built-in guides file (guides.json) that ships inside the plugin. */
class GuideData
{
	/** When the file was made, so we know how fresh it is. */
	String generated;

	List<MonsterGuide> guides = new ArrayList<>();

	/** Item name (lowercase) -> game item ID, so RuneLite can draw the item's icon. */
	Map<String, Integer> itemIds = new LinkedHashMap<>();

	/**
	 * Group names (lowercase) -> the one version we use as its picture.
	 * e.g. "god cape" -> "Saradomin cape", "blessed body" -> "Saradomin d'hide body".
	 */
	Map<String, String> groupItems = new LinkedHashMap<>();
}
