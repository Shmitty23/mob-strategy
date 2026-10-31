package com.mobstrategy;

/** One section of text from a strategy page, e.g. "Phase 2". */
class GuideSection
{
	/** The section heading. */
	String heading;

	/** 2 for a main heading, 3+ for smaller sub-headings. */
	int level;

	/**
	 * Plain text. Lines starting with "• " are bullet points,
	 * and each two leading spaces means one level of indent.
	 */
	String text;
}
