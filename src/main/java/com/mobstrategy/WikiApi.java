package com.mobstrategy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.HttpUrl;
import okhttp3.Request;

/**
 * How we talk to the OSRS Wiki. Used by both the plugin (live lookups)
 * and the GuideGenerator script (building the built-in guides file).
 */
final class WikiApi
{
	static final String API_URL = "https://oldschool.runescape.wiki/api.php";
	static final String PAGE_URL = "https://oldschool.runescape.wiki/w/";

	/** The wiki asks every program to say who it is, so it can contact us if something goes wrong. */
	static final String USER_AGENT = "mob-strategy RuneLite plugin (https://github.com/Shmitty23/mob-strategy)";

	/** The wiki lets us ask for up to 50 pages' text in one request. */
	static final int MAX_TITLES_PER_REQUEST = 50;

	private WikiApi()
	{
	}

	/** Builds a request for the raw text of up to 50 pages. */
	static Request pageTextRequest(List<String> titles)
	{
		HttpUrl url = HttpUrl.parse(API_URL).newBuilder()
			.addQueryParameter("action", "query")
			.addQueryParameter("prop", "revisions")
			.addQueryParameter("rvprop", "content")
			.addQueryParameter("rvslots", "main")
			.addQueryParameter("redirects", "1")
			.addQueryParameter("format", "json")
			.addQueryParameter("formatversion", "2")
			.addQueryParameter("titles", String.join("|", titles))
			.build();
		return request(url);
	}

	static Request request(HttpUrl url)
	{
		return new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.build();
	}

	/**
	 * Reads the wiki's answer into a map of (the title we asked for) -> page text.
	 * The wiki may tidy up a title ("shark" -> "Shark") or follow a redirect to a renamed
	 * page, so we follow those back to the names we asked for.
	 */
	static Map<String, String> readPageTexts(JsonObject response, List<String> askedFor)
	{
		Map<String, String> texts = new LinkedHashMap<>();
		JsonObject query = response.getAsJsonObject("query");
		if (query == null || !query.has("pages"))
		{
			return texts;
		}

		Map<String, String> byTitle = new LinkedHashMap<>();
		for (JsonElement p : query.getAsJsonArray("pages"))
		{
			JsonObject page = p.getAsJsonObject();
			if (page.has("missing") || !page.has("revisions"))
			{
				continue;
			}
			String text = page.getAsJsonArray("revisions").get(0).getAsJsonObject()
				.getAsJsonObject("slots").getAsJsonObject("main")
				.get("content").getAsString();
			byTitle.put(page.get("title").getAsString(), text);
		}

		Map<String, String> normalized = readFromTo(query, "normalized");
		Map<String, String> redirects = readFromTo(query, "redirects");
		for (String title : askedFor)
		{
			String resolved = normalized.getOrDefault(title, title);
			resolved = redirects.getOrDefault(resolved, resolved);
			String text = byTitle.get(resolved);
			if (text != null)
			{
				texts.put(title, text);
			}
		}
		return texts;
	}

	/** Reads the wiki's "this title became that title" lists. */
	static Map<String, String> readFromTo(JsonObject query, String key)
	{
		Map<String, String> map = new LinkedHashMap<>();
		if (query.has(key))
		{
			for (JsonElement e : query.getAsJsonArray(key))
			{
				JsonObject o = e.getAsJsonObject();
				map.put(o.get("from").getAsString(), o.get("to").getAsString());
			}
		}
		return map;
	}

	/** Link to a page in the browser. */
	static String pageLink(String title)
	{
		return PAGE_URL + title.replace(' ', '_');
	}
}
