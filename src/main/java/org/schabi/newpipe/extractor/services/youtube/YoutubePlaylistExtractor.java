package org.schabi.newpipe.extractor.services.youtube;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.schabi.newpipe.extractor.Downloader;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.UrlIdHandler;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.stream.StreamInfoItemsCollector;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.utils.Parser;
import org.schabi.newpipe.extractor.utils.Utils;

import javax.annotation.Nonnull;
import java.io.IOException;

@SuppressWarnings("WeakerAccess")
public class YoutubePlaylistExtractor extends PlaylistExtractor {

    private Document doc;
    /**
     * It's lazily initialized (when getInfoItemPage is called)
     */
    private Document nextStreamsAjax;

    public YoutubePlaylistExtractor(StreamingService service, String url, String nextPageUrl) throws IOException, ExtractionException {
        super(service, url, nextPageUrl);
    }

    @Override
    public void onFetchPage(@Nonnull Downloader downloader) throws IOException, ExtractionException {
        String pageContent = downloader.download(getCleanUrl());
        doc = Jsoup.parse(pageContent, getCleanUrl());

        nextPageUrl = getNextPageUrlFrom(doc);
        nextStreamsAjax = null;
    }

    @Nonnull
    @Override
    public String getId() throws ParsingException {
        try {
            return getUrlIdHandler().getId(getCleanUrl());
        } catch (Exception e) {
            throw new ParsingException("Could not get playlist id");
        }
    }

    @Nonnull
    @Override
    public String getName() throws ParsingException {
        try {
            return doc.select("div[id=pl-header] h1[class=pl-header-title]").first().text();
        } catch (Exception e) {
            throw new ParsingException("Could not get playlist name");
        }
    }

    @Override
    public String getThumbnailUrl() throws ParsingException {
        try {
            return doc.select("div[id=pl-header] div[class=pl-header-thumb] img").first().attr("abs:src");
        } catch (Exception e) {
            throw new ParsingException("Could not get playlist thumbnail");
        }
    }

    @Override
    public String getBannerUrl() throws ParsingException {
        try {
            Element el = doc.select("div[id=\"gh-banner\"] style").first();
            String cssContent = el.html();
            String url = "https:" + Parser.matchGroup1("url\\((.*)\\)", cssContent);
            if (url.contains("s.ytimg.com")) {
                return null;
            } else {
                return url.substring(0, url.indexOf(");"));
            }


        } catch (Exception e) {
            throw new ParsingException("Could not get playlist Banner");
        }
    }

    @Override
    public String getUploaderUrl() throws ParsingException {
        try {
            return doc.select("ul[class=\"pl-header-details\"] li").first().select("a").first().attr("abs:href");
        } catch (Exception e) {
            throw new ParsingException("Could not get playlist uploader name");
        }
    }

    @Override
    public String getUploaderName() throws ParsingException {
        try {
            return doc.select("span[class=\"qualified-channel-title-text\"]").first().select("a").first().text();
        } catch (Exception e) {
            throw new ParsingException("Could not get playlist uploader name");
        }
    }

    @Override
    public String getUploaderAvatarUrl() throws ParsingException {
        try {
            return doc.select("div[id=gh-banner] img[class=channel-header-profile-image]").first().attr("abs:src");
        } catch (Exception e) {
            throw new ParsingException("Could not get playlist uploader avatar");
        }
    }

    @Override
    public long getStreamCount() throws ParsingException {
        String input;

        try {
            input = doc.select("ul[class=\"pl-header-details\"] li").get(1).text();
        } catch (IndexOutOfBoundsException e) {
            throw new ParsingException("Could not get video count from playlist", e);
        }

        try {
            return Long.parseLong(Utils.removeNonDigitCharacters(input));
        } catch (NumberFormatException e) {
            // When there's no videos in a playlist, there's no number in the "innerHtml",
            // all characters that is not a number is removed, so we try to parse a empty string
            if (!input.isEmpty()) {
                return 0;
            } else {
                throw new ParsingException("Could not handle input: " + input, e);
            }
        }
    }

    @Nonnull
    @Override
    public StreamInfoItemsCollector getStreams() throws IOException, ExtractionException {
        StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        Element tbody = doc.select("tbody[id=\"pl-load-more-destination\"]").first();
        collectStreamsFrom(collector, tbody);
        return collector;
    }

    @Override
    public InfoItemPage getInfoItemPage() throws IOException, ExtractionException {
        if (!hasNextPage()) {
            throw new ExtractionException("Playlist doesn't have more streams");
        }

        StreamInfoItemsCollector collector = new StreamInfoItemsCollector(getServiceId());
        setupNextStreamsAjax(NewPipe.getDownloader());
        collectStreamsFrom(collector, nextStreamsAjax.select("tbody[id=\"pl-load-more-destination\"]").first());

        return new InfoItemPage(collector, nextPageUrl);
    }

    private void setupNextStreamsAjax(Downloader downloader) throws IOException, ReCaptchaException, ParsingException {
        String ajaxDataRaw = downloader.download(nextPageUrl);
        try {
            JsonObject ajaxData = JsonParser.object().from(ajaxDataRaw);

            String htmlDataRaw = "<table><tbody id=\"pl-load-more-destination\">" + ajaxData.getString("content_html") + "</tbody></table>";
            nextStreamsAjax = Jsoup.parse(htmlDataRaw, nextPageUrl);

            String nextStreamsHtmlDataRaw = ajaxData.getString("load_more_widget_html");
            if (!nextStreamsHtmlDataRaw.isEmpty()) {
                nextPageUrl = getNextPageUrlFrom(Jsoup.parse(nextStreamsHtmlDataRaw, nextPageUrl));
            } else {
                nextPageUrl = "";
            }
        } catch (JsonParserException e) {
            throw new ParsingException("Could not parse json data for next streams", e);
        }
    }

    private String getNextPageUrlFrom(Document d) throws ParsingException {
        try {
            Element button = d.select("button[class*=\"yt-uix-load-more\"]").first();
            if (button != null) {
                return button.attr("abs:data-uix-load-more-href");
            } else {
                // Sometimes playlists are simply so small, they don't have a more streams/videos
                return "";
            }
        } catch (Exception e) {
            throw new ParsingException("could not get next streams' url", e);
        }
    }

    private void collectStreamsFrom(StreamInfoItemsCollector collector, Element element) throws ParsingException {
        collector.reset();

        final UrlIdHandler streamUrlIdHandler = getService().getStreamUrlIdHandler();
        for (final Element li : element.children()) {
            if(isDeletedItem(li)) {
                continue;
            }

            collector.commit(new YoutubeStreamInfoItemExtractor(li) {
                public Element uploaderLink;

                @Override
                public boolean isAd() throws ParsingException {
                    return false;
                }

                @Override
                public String getUrl() throws ParsingException {
                    try {
                        return streamUrlIdHandler.getUrl(li.attr("data-video-id"));
                    } catch (Exception e) {
                        throw new ParsingException("Could not get web page url for the video", e);
                    }
                }

                @Override
                public String getName() throws ParsingException {
                    try {
                        return li.attr("data-title");
                    } catch (Exception e) {
                        throw new ParsingException("Could not get title", e);
                    }
                }

                @Override
                public long getDuration() throws ParsingException {
                    try {
                        if (getStreamType() == StreamType.LIVE_STREAM) return -1;

                        Element first = li.select("div[class=\"timestamp\"] span").first();
                        if (first == null) {
                            // Video unavailable (private, deleted, etc.), this is a thing that happens specifically with playlists,
                            // because in other cases, those videos don't even show up
                            return -1;
                        }

                        return YoutubeParsingHelper.parseDurationString(first.text());
                    } catch (Exception e) {
                        throw new ParsingException("Could not get duration" + getUrl(), e);
                    }
                }


                private Element getUploaderLink() {
                    // should always be present since we filter deleted items
                    if(uploaderLink == null) {
                        uploaderLink = li.select("div[class=pl-video-owner] a").first();
                    }
                    return uploaderLink;
                }

                @Override
                public String getUploaderName() throws ParsingException {
                    return getUploaderLink().text();
                }

                @Override
                public String getUploaderUrl() throws ParsingException {
                    return getUploaderLink().attr("abs:href");
                }

                @Override
                public String getUploadDate() throws ParsingException {
                    return "";
                }

                @Override
                public long getViewCount() throws ParsingException {
                    return -1;
                }

                @Override
                public String getThumbnailUrl() throws ParsingException {
                    try {
                        return "https://i.ytimg.com/vi/" + streamUrlIdHandler.getId(getUrl()) + "/hqdefault.jpg";
                    } catch (Exception e) {
                        throw new ParsingException("Could not get thumbnail url", e);
                    }
                }
            });
        }
    }

    /**
     * Check if the playlist item is deleted
     * @param li the list item
     * @return true if the item is deleted
     */
    private boolean isDeletedItem(Element li) {
        return li.select("div[class=pl-video-owner] a").isEmpty();
    }
}
