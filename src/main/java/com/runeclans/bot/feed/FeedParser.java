package com.runeclans.bot.feed;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Turns what YouTube and Reddit return into {@link FeedItem}s, newest first. Pure: no network. */
public final class FeedParser {
    private FeedParser() {
    }

    /** Entries of a YouTube channel's Atom feed. Throws if the text isn't a feed. */
    public static List<FeedItem> youtube(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList entries = document.getElementsByTagName("entry");
            List<FeedItem> items = new ArrayList<>();
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String videoId = text(entry, "yt:videoId");
                String title = text(entry, "title");
                if (videoId.isEmpty() || title.isEmpty()) continue;
                Element author = first(entry, "author");
                items.add(new FeedItem(videoId, title, "https://www.youtube.com/watch?v=" + videoId, author == null ? "" : text(author, "name")));
            }
            return items;
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a readable YouTube feed", e);
        }
    }

    /**
     * Posts of a subreddit's {@code new.json}. NSFW posts and pinned (stickied) posts are always left out; when {@code flair} is not
     * blank only posts with that flair (case-insensitive) are kept.
     */
    public static List<FeedItem> reddit(String json, String flair) {
        try {
            DataArray children = DataObject.fromJson(json).getObject("data").getArray("children");
            List<FeedItem> items = new ArrayList<>();
            for (int i = 0; i < children.length(); i++) {
                DataObject post = children.getObject(i).getObject("data");
                if (post.getBoolean("over_18", false) || post.getBoolean("stickied", false)) continue;
                if (flair != null && !flair.isBlank()) {
                    String actual = post.isNull("link_flair_text") ? "" : post.getString("link_flair_text", "");
                    if (!actual.strip().equalsIgnoreCase(flair.strip())) continue;
                }
                String id = post.getString("id", "");
                String title = post.getString("title", "");
                String permalink = post.getString("permalink", "");
                if (id.isEmpty() || title.isEmpty() || !permalink.startsWith("/")) continue;
                items.add(new FeedItem(id, title, "https://www.reddit.com" + permalink, post.getString("author", "")));
            }
            return items;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a readable Reddit listing", e);
        }
    }

    private static Element first(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? null : (Element) list.item(0);
    }

    private static String text(Element parent, String tag) {
        Element element = first(parent, tag);
        return element == null ? "" : element.getTextContent().strip();
    }
}
