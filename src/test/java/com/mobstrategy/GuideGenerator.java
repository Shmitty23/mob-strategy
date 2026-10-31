package com.mobstrategy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Builds the guides.json file that ships inside the plugin.
 *
 * Run this on your own computer before each release (click "Run" above main in VS Code).
 * It is NOT part of the plugin: players never run it, so it never contacts the wiki for them.
 *
 * What it does:
 *  1. Finds every strategy page ("X/Strategies" and "Slayer task/X").
 *  2. Keeps only monsters and bosses: Slayer task pages, plus pages whose main page
 *     is a monster. Raids, minigames and skilling pages are left out.
 *  3. Downloads the pages and reads gear, inventories and tactics out of them.
 *  4. Looks up the game item ID of every item, so RuneLite can draw its icon.
 *  5. Saves it all to src/main/resources/com/mobstrategy/guides.json
 */
public class GuideGenerator
{
	private static final String OUTPUT = "src/main/resources/com/mobstrategy/guides.json";

	/** Always left out, even if the wiki counts them as monsters. */
	private static final Set<String> ALWAYS_EXCLUDE = lowercase(
		"Abyss",
		"Chambers of Xeric",
		"Theatre of Blood",
		"Tombs of Amascut",
		"Tempoross",
		"Wintertodt",
		"Zalcano",
		"Guardians of the Rift"
	);

	/** Always kept: boss groups whose main page isn't a single monster. */
	private static final Set<String> ALWAYS_INCLUDE = lowercase(
		"Dagannoth Kings",
		"Royal Titans",
		"Moons of Peril",
		"Metal dragons",
		"Grotesque Guardians",
		"Revenants",
		"Fossil Island Wyverns",
		"Barrows"
	);

	/** Specific pages to leave out (duplicates or ones we don't want). */
	private static final Set<String> EXCLUDE_PAGES = new HashSet<>(Arrays.asList(
		"Hespori (Echo)/Strategies",
		"Slayer task/Fossil Island wyverns"
	));

	/**
	 * Where to take a monster's picture from, when its own page shows an area or a group.
	 * Guide name (lowercase) -> wiki page with the picture we want.
	 */
	private static final Map<String, String> PICTURE_FROM = new HashMap<>();

	static
	{
		PICTURE_FROM.put("barrows", "Ahrim the Blighted");
		PICTURE_FROM.put("fossil island wyverns", "Spitting wyvern");
		PICTURE_FROM.put("metal dragons", "Bronze dragon");
		PICTURE_FROM.put("revenants", "Revenant knight");
		PICTURE_FROM.put("trolls", "Mountain troll");
		PICTURE_FROM.put("dagannoth kings", "Dagannoth Rex");
		PICTURE_FROM.put("custodian stalker", "Mature custodian stalker");
	}

	/** Exact picture files to use. Guide name (lowercase) -> file name on the wiki. */
	private static final Map<String, String> PICTURE_FILE = new HashMap<>();

	static
	{
		PICTURE_FILE.put("mad angel", "Mad Angel (dormant).png");
	}

	private final OkHttpClient http = new OkHttpClient();
	private final Gson gson = new Gson();

	public static void main(String[] args) throws Exception
	{
		Path output = Paths.get(args.length > 0 ? args[0] : OUTPUT);
		new GuideGenerator().run(output);
	}

	private void run(Path output) throws Exception
	{
		// 1. every strategy page
		Set<String> titles = new TreeSet<>();
		titles.addAll(listPages("embeddedin", "ei", "eititle", "Template:Recommended equipment"));
		titles.addAll(listPages("categorymembers", "cm", "cmtitle", "Category:Strategies"));
		titles.removeIf(t -> !isStrategyPage(t));
		System.out.println("Found " + titles.size() + " strategy pages.");

		// 2. keep monsters and bosses only
		List<String> kept = keepMonstersOnly(titles);
		System.out.println("Keeping " + kept.size() + " monster and boss pages. Downloading...");

		// 3. download and read each page
		GuideData data = new GuideData();
		data.generated = LocalDate.now().toString();
		Set<String> usedNames = new HashSet<>();

		for (List<String> batch : batches(kept))
		{
			Map<String, String> texts = WikiApi.readPageTexts(get(WikiApi.pageTextRequest(batch)), batch);
			for (String title : batch)
			{
				String text = texts.get(title);
				if (text == null)
				{
					System.out.println("  skipped (no text): " + title);
					continue;
				}
				MonsterGuide guide = WikiParser.parse(title, text);
				if (guide.setups.isEmpty() && guide.tactics.isEmpty() && guide.gearNotes.isEmpty())
				{
					System.out.println("  skipped (nothing useful): " + title);
					continue;
				}
				// Two pages could end up with the same short name; keep both apart
				if (!usedNames.add(guide.name.toLowerCase(Locale.ROOT)))
				{
					guide.name = guide.name + (title.startsWith("Slayer task/") ? " (Slayer task)" : " (" + title + ")");
					usedNames.add(guide.name.toLowerCase(Locale.ROOT));
				}
				data.guides.add(guide);
			}
			pause();
		}
		data.guides.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

		// 4. item IDs for icons
		data.itemIds = lookUpItemIds(data.guides, data.groupItems);

		// 5. a small picture of each monster
		downloadMonsterPictures(data.guides, output.toAbsolutePath().getParent().resolve("monsters"));

		// 6. save
		Files.createDirectories(output.toAbsolutePath().getParent());
		Gson writer = new GsonBuilder().disableHtmlEscaping().create();
		try (Writer w = Files.newBufferedWriter(output, StandardCharsets.UTF_8))
		{
			writer.toJson(data, w);
		}
		long withGear = data.guides.stream().filter(g -> !g.setups.isEmpty()).count();
		long withInventory = data.guides.stream().filter(g -> !g.inventories.isEmpty()).count();
		long withPicture = data.guides.stream().filter(g -> g.image != null).count();
		System.out.println("Saved " + data.guides.size() + " guides (" + withGear + " with gear, " + withPicture + " with pictures, "
			+ withInventory + " with inventories, " + data.itemIds.size() + " item icons) to "
			+ output.toAbsolutePath() + " (" + Files.size(output) / 1024 + " KB)");
	}

	/** Boss pages are "X/Strategies"; Slayer pages are "Slayer task/X". */
	private static boolean isStrategyPage(String title)
	{
		return title.contains("/Strateg") || title.startsWith("Slayer task/");
	}

	/** "Vorkath/Strategies" -> "Vorkath". */
	private static String mainPage(String title)
	{
		int slash = title.indexOf('/');
		return slash >= 0 ? title.substring(0, slash) : title;
	}

	/**
	 * Keeps Slayer task pages, plus strategy pages whose main page has a monster
	 * info box (that's how the wiki marks something you can fight).
	 */
	private List<String> keepMonstersOnly(Set<String> titles) throws Exception
	{
		Set<String> mainPages = new TreeSet<>();
		for (String title : titles)
		{
			if (!title.startsWith("Slayer task/"))
			{
				mainPages.add(mainPage(title));
			}
		}

		Set<String> monsters = new HashSet<>();
		for (List<String> batch : batches(new ArrayList<>(mainPages)))
		{
			HttpUrl url = HttpUrl.parse(WikiApi.API_URL).newBuilder()
				.addQueryParameter("action", "query")
				.addQueryParameter("prop", "templates")
				.addQueryParameter("tltemplates", "Template:Infobox Monster")
				.addQueryParameter("tllimit", "max")
				.addQueryParameter("redirects", "1")
				.addQueryParameter("format", "json")
				.addQueryParameter("formatversion", "2")
				.addQueryParameter("titles", String.join("|", batch))
				.build();
			JsonObject query = get(WikiApi.request(url)).getAsJsonObject("query");

			Set<String> monsterTitles = new HashSet<>();
			for (JsonElement p : query.getAsJsonArray("pages"))
			{
				JsonObject page = p.getAsJsonObject();
				if (page.has("templates") && page.getAsJsonArray("templates").size() > 0)
				{
					monsterTitles.add(page.get("title").getAsString());
				}
			}
			Map<String, String> normalized = WikiApi.readFromTo(query, "normalized");
			Map<String, String> redirects = WikiApi.readFromTo(query, "redirects");
			for (String name : batch)
			{
				String resolved = normalized.getOrDefault(name, name);
				resolved = redirects.getOrDefault(resolved, resolved);
				if (monsterTitles.contains(resolved))
				{
					monsters.add(name);
				}
			}
			pause();
		}

		List<String> kept = new ArrayList<>();
		List<String> dropped = new ArrayList<>();
		for (String title : titles)
		{
			String main = mainPage(title).toLowerCase(Locale.ROOT);
			boolean keep = !EXCLUDE_PAGES.contains(title)
				&& (title.startsWith("Slayer task/")
				|| ALWAYS_INCLUDE.contains(main)
				|| (monsters.contains(mainPage(title)) && !ALWAYS_EXCLUDE.contains(main)));
			(keep ? kept : dropped).add(title);
		}
		System.out.println("Left out " + dropped.size() + " pages (raids, minigames, skilling):");
		for (String title : dropped)
		{
			System.out.println("  - " + title);
		}
		return kept;
	}

	/** Finds the game item ID for every item named in the guides. */
	private Map<String, Integer> lookUpItemIds(List<MonsterGuide> guides, Map<String, String> groupItems) throws Exception
	{
		Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (MonsterGuide guide : guides)
		{
			for (GearSetup setup : guide.setups)
			{
				for (GearSlot slot : setup.slots)
				{
					for (GearOption option : slot.options)
					{
						names.addAll(option.items);
					}
				}
			}
			for (InventorySetup inventory : guide.inventories)
			{
				for (InventoryItem item : inventory.slots)
				{
					if (item != null)
					{
						names.add(item.name);
					}
				}
			}
		}
		System.out.println("Looking up icons for " + names.size() + " items...");

		Map<String, Integer> ids = new TreeMap<>();
		// Group pages ("God capes", "Blessed boots") aren't one item; remember the items they list
		Map<String, List<String>> groupPages = new TreeMap<>();
		Map<String, String> groupTexts = new TreeMap<>();
		int done = 0;
		for (List<String> batch : batches(new ArrayList<>(names)))
		{
			Map<String, String> texts = WikiApi.readPageTexts(get(WikiApi.pageTextRequest(batch)), batch);
			for (String name : batch)
			{
				String text = texts.get(name);
				Integer id = text == null ? null : WikiParser.findItemId(text);
				if (id != null)
				{
					ids.put(name.toLowerCase(Locale.ROOT), id);
				}
				else if (text != null)
				{
					groupTexts.put(name, text);
				}
			}
			done += batch.size();
			System.out.println("  " + done + " / " + names.size());
			pause();
		}

		// Group pages often list their items inside navigation boxes, which aren't in the
		// page's own text. So also ask the wiki for every link shown on the finished page.
		System.out.println("Checking " + groupTexts.size() + " group pages (god capes, blessed d'hide...)");
		for (Map.Entry<String, String> group : groupTexts.entrySet())
		{
			List<String> linked = new ArrayList<>(WikiParser.linkedNames(group.getValue()));
			for (String link : allLinksOn(group.getKey()))
			{
				if (!linked.contains(link))
				{
					linked.add(link);
				}
			}
			List<String> best = bestMatches(group.getKey(), linked);
			if (!best.isEmpty())
			{
				groupPages.put(group.getKey(), best);
			}
			pause();
		}

		// Second pass: use the best-matching item listed on each group page as its icon
		Set<String> toFetch = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		for (List<String> listed : groupPages.values())
		{
			for (String item : listed)
			{
				if (!ids.containsKey(item.toLowerCase(Locale.ROOT)))
				{
					toFetch.add(item);
				}
			}
		}
		if (!toFetch.isEmpty())
		{
			System.out.println("Looking up icons for " + groupPages.size() + " group pages (god capes, blessings...)");
			for (List<String> batch : batches(new ArrayList<>(toFetch)))
			{
				Map<String, String> texts = WikiApi.readPageTexts(get(WikiApi.pageTextRequest(batch)), batch);
				for (String name : batch)
				{
					String text = texts.get(name);
					Integer id = text == null ? null : WikiParser.findItemId(text);
					if (id != null)
					{
						ids.put(name.toLowerCase(Locale.ROOT), id);
					}
				}
				pause();
			}
			for (Map.Entry<String, List<String>> group : groupPages.entrySet())
			{
				for (String item : group.getValue())
				{
					Integer id = ids.get(item.toLowerCase(Locale.ROOT));
					if (id != null)
					{
						ids.put(group.getKey().toLowerCase(Locale.ROOT), id);
						groupItems.put(group.getKey().toLowerCase(Locale.ROOT), item);
						break;
					}
				}
			}
		}

		List<String> stillMissing = new ArrayList<>();
		for (String name : names)
		{
			if (!ids.containsKey(name.toLowerCase(Locale.ROOT)))
			{
				stillMissing.add(name);
			}
		}
		System.out.println("No icon found for " + stillMissing.size() + " names: " + stillMissing);
		return ids;
	}

	/**
	 * Out of every page a group page links to, the ones whose names share the most words
	 * with the group's name. "Imbued god cape" -> "Imbued saradomin cape" before "Magic".
	 */
	static List<String> bestMatches(String groupName, List<String> linked)
	{
		Set<String> words = words(groupName);
		List<String> scored = new ArrayList<>();
		for (String candidate : linked)
		{
			if (score(words, candidate) > 0 && !scored.contains(candidate))
			{
				scored.add(candidate);
			}
		}
		scored.sort((a, b) -> score(words, b) - score(words, a)); // keeps page order for ties
		return new ArrayList<>(scored.subList(0, Math.min(12, scored.size())));
	}

	private static int score(Set<String> groupWords, String candidate)
	{
		int score = 0;
		for (String word : words(candidate))
		{
			if (groupWords.contains(word))
			{
				score++;
			}
		}
		return score;
	}

	/** "God capes" -> {"god", "cape"}: lowercase, and "capes" counts the same as "cape". */
	private static Set<String> words(String text)
	{
		Set<String> words = new HashSet<>();
		for (String word : text.toLowerCase(Locale.ROOT).split("[^a-z']+"))
		{
			if (word.length() > 2)
			{
				words.add(word.endsWith("s") ? word.substring(0, word.length() - 1) : word);
			}
		}
		return words;
	}

	/** Size of the monster pictures, in pixels (they're drawn inside a square this big). */
	private static final int PICTURE_SIZE = 28;

	/**
	 * Downloads the picture from each monster's wiki info box, shrinks it to a small icon
	 * and saves it in the plugin's resources (monsters/vorkath.png).
	 */
	private void downloadMonsterPictures(List<MonsterGuide> guides, Path folder) throws Exception
	{
		// Which wiki page shows the monster itself: "Vorkath/Strategies" -> "Vorkath",
		// "Slayer task/Bloodveld" -> "Bloodveld"
		Map<MonsterGuide, String> monsterPage = new LinkedHashMap<>();
		for (MonsterGuide guide : guides)
		{
			String page = guide.page.startsWith("Slayer task/")
				? guide.page.substring("Slayer task/".length())
				: mainPage(guide.page);
			monsterPage.put(guide, PICTURE_FROM.getOrDefault(guide.name.toLowerCase(Locale.ROOT), page));
		}

		// 1. find the picture's file name on each monster page
		Map<String, String> fileOf = new HashMap<>();
		for (List<String> batch : batches(new ArrayList<>(new TreeSet<>(monsterPage.values()))))
		{
			Map<String, String> texts = WikiApi.readPageTexts(get(WikiApi.pageTextRequest(batch)), batch);
			for (String page : batch)
			{
				String file = texts.containsKey(page) ? WikiParser.findMonsterImage(texts.get(page)) : null;
				if (file != null)
				{
					fileOf.put(page, file);
				}
			}
			pause();
		}

		// Pictures we picked by hand
		for (MonsterGuide guide : guides)
		{
			String file = PICTURE_FILE.get(guide.name.toLowerCase(Locale.ROOT));
			if (file != null)
			{
				fileOf.put(monsterPage.get(guide), file);
			}
		}

		// 2. ask the wiki for a small (64px wide) copy of each picture
		Map<String, String> smallCopyUrl = new HashMap<>();
		List<String> fileTitles = new ArrayList<>();
		for (String file : new TreeSet<>(fileOf.values()))
		{
			fileTitles.add("File:" + file);
		}
		for (List<String> batch : batches(fileTitles))
		{
			HttpUrl url = HttpUrl.parse(WikiApi.API_URL).newBuilder()
				.addQueryParameter("action", "query")
				.addQueryParameter("prop", "imageinfo")
				.addQueryParameter("iiprop", "url")
				.addQueryParameter("iiurlwidth", "64")
				.addQueryParameter("format", "json")
				.addQueryParameter("formatversion", "2")
				.addQueryParameter("titles", String.join("|", batch))
				.build();
			JsonObject query = get(WikiApi.request(url)).getAsJsonObject("query");
			Map<String, String> found = new HashMap<>();
			for (JsonElement p : query.getAsJsonArray("pages"))
			{
				JsonObject page = p.getAsJsonObject();
				if (!page.has("imageinfo"))
				{
					continue;
				}
				JsonObject info = page.getAsJsonArray("imageinfo").get(0).getAsJsonObject();
				String link = info.has("thumburl") ? info.get("thumburl").getAsString() : info.get("url").getAsString();
				found.put(page.get("title").getAsString(), link);
			}
			Map<String, String> normalized = WikiApi.readFromTo(query, "normalized");
			for (String title : batch)
			{
				String link = found.get(normalized.getOrDefault(title, title));
				if (link != null)
				{
					smallCopyUrl.put(title.substring("File:".length()), link);
				}
			}
			pause();
		}

		// 3. pages with no info box picture: use the main picture the wiki shows for the page
		List<String> noPicture = new ArrayList<>();
		for (String page : new TreeSet<>(monsterPage.values()))
		{
			if (!fileOf.containsKey(page))
			{
				noPicture.add(page);
			}
		}
		Map<String, String> pagePictureUrl = new HashMap<>();
		for (List<String> batch : batches(noPicture))
		{
			HttpUrl url = HttpUrl.parse(WikiApi.API_URL).newBuilder()
				.addQueryParameter("action", "query")
				.addQueryParameter("prop", "pageimages")
				.addQueryParameter("piprop", "thumbnail")
				.addQueryParameter("pithumbsize", "64")
				.addQueryParameter("pilimit", "max")
				.addQueryParameter("redirects", "1")
				.addQueryParameter("format", "json")
				.addQueryParameter("formatversion", "2")
				.addQueryParameter("titles", String.join("|", batch))
				.build();
			JsonObject query = get(WikiApi.request(url)).getAsJsonObject("query");
			Map<String, String> found = new HashMap<>();
			for (JsonElement p : query.getAsJsonArray("pages"))
			{
				JsonObject page = p.getAsJsonObject();
				if (page.has("thumbnail"))
				{
					found.put(page.get("title").getAsString(), page.getAsJsonObject("thumbnail").get("source").getAsString());
				}
			}
			Map<String, String> normalized = WikiApi.readFromTo(query, "normalized");
			Map<String, String> redirects = WikiApi.readFromTo(query, "redirects");
			for (String page : batch)
			{
				String resolved = normalized.getOrDefault(page, page);
				resolved = redirects.getOrDefault(resolved, resolved);
				if (found.containsKey(resolved))
				{
					pagePictureUrl.put(page, found.get(resolved));
				}
			}
			pause();
		}

		// 4. download, shrink and save. Old pictures are removed first so none go stale.
		Files.createDirectories(folder);
		try (java.util.stream.Stream<Path> old = Files.list(folder))
		{
			for (Path file : (Iterable<Path>) old::iterator)
			{
				if (file.toString().endsWith(".png"))
				{
					Files.delete(file);
				}
			}
		}
		System.out.println("Downloading monster pictures...");
		Map<String, String> savedAs = new HashMap<>();
		List<String> withoutPicture = new ArrayList<>();
		for (MonsterGuide guide : guides)
		{
			String page = monsterPage.get(guide);
			String file = fileOf.get(page);
			String link = file != null ? smallCopyUrl.get(file) : pagePictureUrl.get(page);
			if (link == null)
			{
				withoutPicture.add(guide.name);
				continue;
			}
			if (!savedAs.containsKey(link))
			{
				BufferedImage picture = download(link);
				if (picture == null)
				{
					withoutPicture.add(guide.name);
					continue;
				}
				String name = guide.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + ".png";
				ImageIO.write(shrink(picture), "png", folder.resolve(name).toFile());
				savedAs.put(link, name);
				Thread.sleep(200);
			}
			guide.image = savedAs.get(link);
		}
		System.out.println("No picture for " + withoutPicture.size() + " monsters: " + withoutPicture);
	}

	private BufferedImage download(String link) throws IOException
	{
		Request request = new Request.Builder().url(link).header("User-Agent", WikiApi.USER_AGENT).build();
		try (Response response = http.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				System.out.println("  couldn't download " + link);
				return null;
			}
			return ImageIO.read(new ByteArrayInputStream(response.body().bytes()));
		}
	}

	/** Shrinks a picture to fit a PICTURE_SIZE square, keeping its shape, centered. */
	private static BufferedImage shrink(BufferedImage picture)
	{
		double scale = Math.min((double) PICTURE_SIZE / picture.getWidth(), (double) PICTURE_SIZE / picture.getHeight());
		int width = Math.max(1, (int) Math.round(picture.getWidth() * scale));
		int height = Math.max(1, (int) Math.round(picture.getHeight() * scale));
		BufferedImage small = new BufferedImage(PICTURE_SIZE, PICTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = small.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.drawImage(picture, (PICTURE_SIZE - width) / 2, (PICTURE_SIZE - height) / 2, width, height, null);
		g.dispose();
		return small;
	}

	/** Every main-namespace page linked from a page, including links inside its navigation boxes. */
	private List<String> allLinksOn(String title) throws Exception
	{
		List<String> links = new ArrayList<>();
		String continueFrom = null;
		do
		{
			HttpUrl.Builder url = HttpUrl.parse(WikiApi.API_URL).newBuilder()
				.addQueryParameter("action", "query")
				.addQueryParameter("prop", "links")
				.addQueryParameter("titles", title)
				.addQueryParameter("redirects", "1")
				.addQueryParameter("plnamespace", "0")
				.addQueryParameter("pllimit", "max")
				.addQueryParameter("format", "json")
				.addQueryParameter("formatversion", "2");
			if (continueFrom != null)
			{
				url.addQueryParameter("plcontinue", continueFrom);
			}
			JsonObject response = get(WikiApi.request(url.build()));
			JsonObject query = response.getAsJsonObject("query");
			if (query != null && query.has("pages"))
			{
				for (JsonElement p : query.getAsJsonArray("pages"))
				{
					JsonObject page = p.getAsJsonObject();
					if (page.has("links"))
					{
						for (JsonElement l : page.getAsJsonArray("links"))
						{
							links.add(l.getAsJsonObject().get("title").getAsString());
						}
					}
				}
			}
			JsonObject more = response.getAsJsonObject("continue");
			continueFrom = more != null && more.has("plcontinue") ? more.get("plcontinue").getAsString() : null;
		}
		while (continueFrom != null);
		return links;
	}

	/** Lists every main-namespace page in a category, or every page that uses a template. */
	private Set<String> listPages(String list, String prefix, String titleParam, String title) throws Exception
	{
		Set<String> found = new TreeSet<>();
		String continueFrom = null;
		do
		{
			HttpUrl.Builder url = HttpUrl.parse(WikiApi.API_URL).newBuilder()
				.addQueryParameter("action", "query")
				.addQueryParameter("list", list)
				.addQueryParameter(titleParam, title)
				.addQueryParameter(prefix + "namespace", "0")
				.addQueryParameter(prefix + "limit", "500")
				.addQueryParameter("format", "json")
				.addQueryParameter("formatversion", "2");
			if (continueFrom != null)
			{
				url.addQueryParameter(prefix + "continue", continueFrom);
			}

			JsonObject response = get(WikiApi.request(url.build()));
			for (JsonElement e : response.getAsJsonObject("query").getAsJsonArray(list))
			{
				found.add(e.getAsJsonObject().get("title").getAsString());
			}

			JsonObject more = response.getAsJsonObject("continue");
			continueFrom = more != null && more.has(prefix + "continue")
				? more.get(prefix + "continue").getAsString()
				: null;
			pause();
		}
		while (continueFrom != null);
		return found;
	}

	private static List<List<String>> batches(List<String> all)
	{
		List<List<String>> batches = new ArrayList<>();
		for (int i = 0; i < all.size(); i += WikiApi.MAX_TITLES_PER_REQUEST)
		{
			batches.add(new ArrayList<>(all.subList(i, Math.min(i + WikiApi.MAX_TITLES_PER_REQUEST, all.size()))));
		}
		return batches;
	}

	private JsonObject get(Request request) throws IOException
	{
		try (Response response = http.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new IOException("Wiki request failed: HTTP " + response.code() + " for " + request.url());
			}
			return gson.fromJson(response.body().charStream(), JsonObject.class);
		}
	}

	/** Wait a moment between requests, to be polite to the wiki. */
	private static void pause() throws InterruptedException
	{
		Thread.sleep(500);
	}

	private static Set<String> lowercase(String... names)
	{
		Set<String> set = new HashSet<>();
		Arrays.stream(names).forEach(n -> set.add(n.toLowerCase(Locale.ROOT)));
		return set;
	}
}
