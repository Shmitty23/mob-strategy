package com.mobstrategy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * Holds all the guides.
 *  - "Built-in" guides come from guides.json inside the plugin (no internet needed).
 *  - "Live" guides are downloaded from the wiki when the player turns on Live wiki lookups.
 */
@Slf4j
class GuideStore
{
	private final OkHttpClient http;
	private final Gson gson;

	private final Map<String, MonsterGuide> builtIn = new LinkedHashMap<>();
	private final Map<String, MonsterGuide> live = new ConcurrentHashMap<>();
	private final Map<String, Integer> itemIds = new HashMap<>();
	private final Map<String, String> groupItems = new HashMap<>();
	private final Map<String, ImageIcon> pictures = new HashMap<>();
	private String builtInDate = "unknown";

	GuideStore(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	/** Reads guides.json from inside the plugin. */
	void loadBuiltIn()
	{
		try (InputStream in = GuideStore.class.getResourceAsStream("guides.json"))
		{
			if (in == null)
			{
				log.warn("guides.json is missing - run GuideGenerator to create it");
				return;
			}
			GuideData data = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), GuideData.class);
			if (data == null || data.guides == null)
			{
				return;
			}
			for (MonsterGuide guide : data.guides)
			{
				builtIn.put(guide.name, guide);
			}
			if (data.itemIds != null)
			{
				itemIds.putAll(data.itemIds);
			}
			if (data.groupItems != null)
			{
				groupItems.putAll(data.groupItems);
			}
			if (data.generated != null)
			{
				builtInDate = data.generated;
			}
			log.debug("Loaded {} built-in guides", builtIn.size());
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Couldn't read guides.json", e);
		}
	}

	/** The game item ID for an item name, or -1 if we don't know it (no icon then). */
	int itemId(String itemName)
	{
		if (itemName == null)
		{
			return -1;
		}
		Integer id = itemIds.get(itemName.toLowerCase(Locale.ROOT));
		return id != null ? id : -1;
	}

	/**
	 * For group items like "God cape", the version shown as its picture ("Saradomin cape").
	 * Returns null for normal items.
	 */
	String pictureOf(String itemName)
	{
		return itemName == null ? null : groupItems.get(itemName.toLowerCase(Locale.ROOT));
	}

	/** "any god" for god-themed groups (blessed d'hide, vestments), "any version" for capes and others, or null. */
	String anyVersionNote(String itemName)
	{
		String picture = pictureOf(itemName);
		if (picture == null)
		{
			return null;
		}
		// God capes say "any version", same as imbued god capes
		if (itemName.toLowerCase(Locale.ROOT).contains("cape"))
		{
			return "any version";
		}
		String lower = picture.toLowerCase(Locale.ROOT);
		for (String god : GODS)
		{
			if (lower.startsWith(god))
			{
				return "any god";
			}
		}
		return "any version";
	}

	private static final String[] GODS = {
		"saradomin", "zamorak", "guthix", "armadyl", "bandos", "ancient", "zaros", "seren"
	};

	/** The monster's small picture, or null if it doesn't have one. Loaded once, then remembered. */
	ImageIcon picture(String name)
	{
		MonsterGuide guide = name == null ? null : builtIn.get(name);
		if (guide == null || guide.image == null)
		{
			return null;
		}
		if (!pictures.containsKey(name))
		{
			ImageIcon icon = null;
			try (InputStream in = GuideStore.class.getResourceAsStream("monsters/" + guide.image))
			{
				if (in != null)
				{
					BufferedImage image = ImageIO.read(in);
					icon = image == null ? null : new ImageIcon(image);
				}
			}
			catch (IOException e)
			{
				log.debug("Couldn't load picture for {}", name, e);
			}
			pictures.put(name, icon);
		}
		return pictures.get(name);
	}

	/**
	 * Finds the guide for a Slayer task name like "Bloodveld", "abyssal demons" or "Vorkath".
	 * Returns the guide's name, or null if there isn't one.
	 */
	String findGuideFor(String task)
	{
		String wanted = simplify(task);
		if (wanted.isEmpty())
		{
			return null;
		}
		// 1. same name ("Dust devils" = "Dust devil"), preferring the Slayer task page
		String match = null;
		for (MonsterGuide guide : builtIn.values())
		{
			if (simplify(guide.name.replace("(Slayer task)", "")).equals(wanted))
			{
				if (guide.page.startsWith("Slayer task/"))
				{
					return guide.name;
				}
				match = match == null ? guide.name : match;
			}
		}
		if (match != null)
		{
			return match;
		}
		// 2. one name starts with the other ("Kalphite" -> "Kalphite Queen")
		for (MonsterGuide guide : builtIn.values())
		{
			String name = simplify(guide.name);
			if (name.startsWith(wanted) || wanted.startsWith(name))
			{
				return guide.name;
			}
		}
		return null;
	}

	/** Lowercase, letters and spaces only, and "demons" counts the same as "demon". */
	private static String simplify(String text)
	{
		StringBuilder out = new StringBuilder();
		for (String word : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").trim().split("\\s+"))
		{
			if (word.isEmpty())
			{
				continue;
			}
			if (word.endsWith("s") && word.length() > 3)
			{
				word = word.substring(0, word.length() - 1);
			}
			out.append(out.length() > 0 ? " " : "").append(word);
		}
		return out.toString();
	}

	List<String> names()
	{
		return new ArrayList<>(builtIn.keySet());
	}

	String builtInDate()
	{
		return builtInDate;
	}

	/** The freshest version we have: the live one if downloaded, otherwise built-in. */
	MonsterGuide get(String name)
	{
		MonsterGuide fresh = live.get(name);
		return fresh != null ? fresh : builtIn.get(name);
	}

	boolean hasLive(String name)
	{
		return live.containsKey(name);
	}

	/**
	 * Downloads the latest version of a guide from the wiki, in the background.
	 * The callbacks run on a background thread, so the panel must switch back to the UI thread.
	 */
	void fetchLive(String name, Consumer<MonsterGuide> onSuccess, Runnable onFailure)
	{
		MonsterGuide base = builtIn.get(name);
		if (base == null)
		{
			onFailure.run();
			return;
		}

		http.newCall(WikiApi.pageTextRequest(Collections.singletonList(base.page))).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Wiki lookup failed for {}", base.page, e);
				onFailure.run();
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						onFailure.run();
						return;
					}
					JsonObject json = gson.fromJson(r.body().charStream(), JsonObject.class);
					String text = WikiApi.readPageTexts(json, Collections.singletonList(base.page)).get(base.page);
					if (text == null)
					{
						onFailure.run();
						return;
					}
					MonsterGuide guide = WikiParser.parse(base.page, text);
					guide.name = base.name;
					guide.image = base.image;
					live.put(name, guide);
					onSuccess.accept(guide);
				}
				catch (RuntimeException e)
				{
					log.debug("Couldn't read wiki page {}", base.page, e);
					onFailure.run();
				}
			}
		});
	}
}
