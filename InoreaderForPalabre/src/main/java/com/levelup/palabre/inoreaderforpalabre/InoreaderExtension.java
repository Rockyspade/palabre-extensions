package com.levelup.palabre.inoreaderforpalabre;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.levelup.palabre.api.ExtensionUpdateStatus;
import com.levelup.palabre.api.PalabreExtension;
import com.levelup.palabre.api.datamapping.Article;
import com.levelup.palabre.api.datamapping.Category;
import com.levelup.palabre.api.datamapping.DataMappingOptions;
import com.levelup.palabre.api.datamapping.Source;
import com.levelup.palabre.inoreaderforpalabre.core.SharedPreferenceKeys;
import com.levelup.palabre.inoreaderforpalabre.inoreader.InoreaderService;
import com.levelup.palabre.inoreaderforpalabre.inoreader.InoreaderServiceInterface;
import com.levelup.palabre.inoreaderforpalabre.inoreader.data.Subscription;
import com.levelup.palabre.inoreaderforpalabre.inoreader.data.SubscriptionList;
import com.levelup.palabre.inoreaderforpalabre.inoreader.data.folderlist.FolderList;
import com.levelup.palabre.inoreaderforpalabre.inoreader.data.folderlist.Tag;
import com.levelup.palabre.inoreaderforpalabre.inoreader.data.preferencestreamlist.Pref;
import com.levelup.palabre.inoreaderforpalabre.inoreader.data.preferencestreamlist.Streamprefs;
import com.levelup.palabre.inoreaderforpalabre.inoreader.data.streamcontent.Item;
import com.levelup.palabre.inoreaderforpalabre.inoreader.data.streamcontent.StreamContent;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.lang.reflect.Type;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import retrofit.RetrofitError;

/**
 * Created by nicolas on 01/06/15.
 */
public class InoreaderExtension extends PalabreExtension {
    public static final String UNCATEGORIZED_ID = "subscriptions";
    private static String TAG = InoreaderExtension.class.getSimpleName();
    private Looper serviceLooper;
    private Handler serviceHandler;
    private boolean updateRunning;

    /**
     * Called when Palabre need this extension to update its data
     */
    @Override
    protected void onUpdateData() {

        if (updateRunning) {
            return;
        }
        updateRunning = true;

        if (BuildConfig.DEBUG) Log.d(TAG, "Update data launched");

        DataMappingOptions.getInstance().setDebug(BuildConfig.DEBUG);

        //We start updating
        publishUpdateStatus(new ExtensionUpdateStatus().start());

        //Get a non UI thread and Handler
        HandlerThread thread = new HandlerThread("PalabreExtension:" + getClass().getSimpleName());
        thread.start();

        serviceLooper = thread.getLooper();
        serviceHandler = new Handler(serviceLooper);


        //Let's refresh the categories and sources. It's static so we can call it from outside (to populate the in-app list).
        refreshCategoriesAndSources(this, new OnCategoryAndSourceRefreshed() {
            @Override
            public void onFinished() {

                //It's done. Let's refresh the articles
                refreshArticles(InoreaderExtension.this);


            }

            @Override
            public void onFailure(RetrofitError retrofitError) {
                endUpdate(true, retrofitError);
            }

            @Override
            public void onprogressChanged(int progress) {
                publishUpdateStatus(new ExtensionUpdateStatus().progress(progress));
            }
        });


    }

    /**
     * Refresh the articles.
     * @param context the Context to use
     */
    private void refreshArticles(final Context context) {

        //We use the prepared thread to avoid doing Network operations on main thread
        serviceHandler.post(new Runnable() {
            @Override
            public void run() {
                final String userId = PreferenceManager.getDefaultSharedPreferences(context).getString(SharedPreferenceKeys.USER_ID, "");
                final List<Source> savedSources = Source.getAll(context);

                String continuation = null;
                int progress = 30;

                if (BuildConfig.DEBUG) Log.d(TAG, "Tracking time: before getting all articles");
                List<Article> allArticles = Article.getAll(context);
                if (BuildConfig.DEBUG) Log.d(TAG, "Tracking time: after getting all articles");


                for (int i = 0; i < 5; i++) {

                    publishUpdateStatus(new ExtensionUpdateStatus().progress(progress));
                    progress += 5;
                    try {
                        continuation = loadSetOfArticles(context, userId, savedSources, allArticles, continuation);
                        publishUpdateStatus(new ExtensionUpdateStatus().progress(progress));
                        progress += 5;
                    } catch (RetrofitError e) {
                        endUpdate(true, e);
                    }
                }

                refreshStarredArticles(userId, savedSources);


            }
        });


    }

    /**
     * Load a set of articles and return the continuation ID
     * @param context the Context to use
     * @param userId the user id used for the request
     * @param savedSources the complete list of sources (cached for better performances)
     * @param allArticles the complete list of article (cached for better performances)
     * @param continuation the continuation ID to use
     * @return a continuation ID
     */
    public String loadSetOfArticles(Context context, String userId, List<Source> savedSources, List<Article> allArticles, String continuation) {

        //Load articles
        StreamContent response = InoreaderService.getInstance(context).getStreamContent(continuation);

        final List<Article> articles = new ArrayList<>();
        for (Item item : response.getItems()) {

            boolean needToUpdate = true;
            for (Article allArticle : allArticles) {
                if (allArticle.getUniqueId().equals(item.getId())) {

                    boolean readState = item.getCategories().contains("user/" + userId + "/state/com.google/read");

                    if (allArticle.isRead() == readState) {
                        needToUpdate = false;
                        break;
                    }
                }
            }

            if (needToUpdate) {
                addArticleToList(userId, savedSources, articles, item);
            }
        }
        Article.multipleSave(context, articles);
        return response.getContinuation();
    }


    /**
     * Refresh the starred articles
     * @param userId the user ID to use
     * @param savedSources the complete list of sources (cached for better performances)
     */
    public void refreshStarredArticles(final String userId, final List<Source> savedSources) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Refresh Starred articles");
        //get all the starred items
        InoreaderService.getInstance(InoreaderExtension.this).getStreamContentStarred(new InoreaderServiceInterface.IRequestListener<StreamContent>() {
            @Override
            public void onFailure(RetrofitError retrofitError) {
                endUpdate(true, retrofitError);

            }

            @Override
            public void onSuccess(StreamContent response) {

                List<Article> articles = new ArrayList<Article>();

                for (Item item : response.getItems()) {
                    addArticleToList(userId, savedSources, articles, item);
                }

                if (BuildConfig.DEBUG) Log.d(TAG, "Starred items: " + articles.size());

                Article.multipleSave(InoreaderExtension.this, articles);


                endUpdate(false, null);
            }
        });
    }

    /**
     * End the refresh process
     * @param isFailure is it the result of a failure?
     * @param retrofitError the error sent
     */
    public void endUpdate(boolean isFailure, RetrofitError retrofitError) {
        updateRunning = false;
        //It's a filure
        if (isFailure) {

            //Let's see if it can be explained
            String errorString = getResources().getString(R.string.refresh_error, "");
            if (retrofitError != null && retrofitError.getCause() instanceof UnknownHostException) {
                //it looks like a connection issue
                errorString = getResources().getString(R.string.refresh_error_connection);

            } else if (retrofitError != null) {
                errorString = getResources().getString(R.string.refresh_error, "\n" + retrofitError.getMessage());
            }

            //Send the fail result with the explanation that will be shown in Palabre
            publishUpdateStatus(new ExtensionUpdateStatus().fail(errorString));

        } else {

            //Everything was fine. Let's end the update.
            publishUpdateStatus(new ExtensionUpdateStatus().stop());
        }
    }

    /**
     * Refresh the categories and sources. This method is static to allow calls from outside of the service.
     * @param context the Context to be used
     * @param listener a listener that sends the result ad progress back
     */
    public static void refreshCategoriesAndSources(final Context context, final OnCategoryAndSourceRefreshed listener) {
        InoreaderService.getInstance(context).getStreamPreferenceList(new InoreaderServiceInterface.IRequestListener<String>() {
            @Override
            public void onFailure(RetrofitError retrofitError) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Failure", retrofitError);
                if (listener != null) {
                    listener.onFailure(retrofitError);
                }
            }

            @Override
            public void onSuccess(String response) {
                if (BuildConfig.DEBUG) Log.d(TAG, "response");

                if (listener != null) listener.onprogressChanged(5);

                Type mapStringObjectType = new TypeToken<Map<String, Object>>() {
                }.getType();
                GsonBuilder gsonBuilder = new GsonBuilder();
                gsonBuilder.registerTypeAdapter(mapStringObjectType, new Streamprefs());
                Gson gson = gsonBuilder.create();

                Map<String, Object> map = gson.fromJson(response, mapStringObjectType);
                System.out.println(map);

                final Map<String, List<String>> sortOrders = new HashMap<>();


                for (Map.Entry<String, Object> stringObjectEntry : map.entrySet()) {
                    if (BuildConfig.DEBUG)
                        Log.d(TAG, "type: " + stringObjectEntry.getValue().getClass().getSimpleName());
                    for (Map.Entry<String, Object> objectEntry : ((Map<String, Object>) stringObjectEntry.getValue()).entrySet()) {
                        if (BuildConfig.DEBUG)
                            Log.d(TAG, "type2: " + objectEntry.getKey() + " => " + objectEntry.getValue().getClass().getSimpleName());


                        for (Pref pref : ((List<Pref>) objectEntry.getValue())) {
                            if (pref.getId().equals("subscription-ordering")) {


                                List<String> orders = new ArrayList<>();
                                for (int j = 0; j < pref.getValue().length(); j += 8) {
                                    orders.add(pref.getValue().substring(j, j + 8));
                                }

                                sortOrders.put(objectEntry.getKey(), orders);
                            }
                        }

                    }
                }

                if (listener != null) listener.onprogressChanged(6);


                //get folders
                InoreaderService.getInstance(context).getFolderList(new InoreaderServiceInterface.IRequestListener<FolderList>() {
                    @Override
                    public void onFailure(RetrofitError retrofitError) {
                        if (BuildConfig.DEBUG) Log.d(TAG, "Error");
                        if (listener != null) {
                            listener.onFailure(retrofitError);
                        }
                    }

                    @Override
                    public void onSuccess(FolderList response) {
                        if (listener != null) listener.onprogressChanged(10);

                        List<Tag> orderedTags = new ArrayList<>();
                        for (Tag tag : response.getTags()) {
                            if (BuildConfig.DEBUG) Log.d(TAG, "Folder: " + tag.getId());

                            if (tag.getId().contains("/label/")) {
                                orderedTags.add(tag);
                            }

                        }

                        Collections.sort(orderedTags, new Comparator<Tag>() {
                            @Override
                            public int compare(Tag lhs, Tag rhs) {
                                if (lhs.getSortid() == null) {
                                    return -1;
                                }
                                if (rhs.getSortid() == null) {
                                    return 1;
                                }
                                final List<String> orders = sortOrders.get("user/1005688236/state/com.google/root");
                                if (orders == null) {
                                    return 0;
                                }
                                return orders.indexOf(lhs.getSortid()) - orders.indexOf(rhs.getSortid());

                            }
                        });

                        List<Category> categories = new ArrayList<>();
                        for (Tag orderedTag : orderedTags) {
                            if (BuildConfig.DEBUG)
                                Log.d(TAG, "Sorted folder: " + orderedTag.getId());

                            String[] splitTagId = orderedTag.getId().split("/");

                            categories.add(new Category()
                                    .title(splitTagId[splitTagId.length - 1])
                                    .uniqueId(orderedTag.getId()));


                        }

                        Category.multipleSave(context, categories);

                        //remove non existing categories
                        List<Category> allCats = Category.getAll(context);
                        for (Category allCat : allCats) {
                            boolean found = false;
                            for (Tag orderedTag : orderedTags) {
                                if (allCat.getUniqueId().equals(orderedTag.getId())) {
                                    found = true;
                                    break;
                                }
                            }

                            if (!found && !allCat.getUniqueId().equals(UNCATEGORIZED_ID)) {
                                allCat.delete(context);
                            }

                        }


                        if (listener != null) listener.onprogressChanged(15);


                        //Loading sources
                        InoreaderService.getInstance(context).getSubscriptionList(new InoreaderServiceInterface.IRequestListener<SubscriptionList>() {
                            @Override
                            public void onFailure(RetrofitError retrofitError) {
                                if (BuildConfig.DEBUG) Log.d(TAG, "failure");
                                if (listener != null) {
                                    listener.onFailure(retrofitError);
                                }
                            }

                            @Override
                            public void onSuccess(SubscriptionList response) {

                                if (listener != null) listener.onprogressChanged(20);

                                List<Source> sources = new ArrayList<>();

                                List<Category> allCategories = Category.getAll(context);

                                for (Subscription subscription : response.getSubscriptions()) {

                                    Set<Category> categoryList = new HashSet<>();
                                    for (com.levelup.palabre.inoreaderforpalabre.inoreader.data.Category category : subscription.getCategories()) {
                                        for (Category allCategory : allCategories) {
                                            if (allCategory.getUniqueId().equals(category.getId())) {
                                                categoryList.add(allCategory);
                                            }
                                        }
                                    }

                                    if (subscription.getCategories().size() == 0) {
                                        Category uncat = getUncatCategory(context);
                                        categoryList.add(uncat);


                                    }


                                    sources.add(new Source()
                                            .title(subscription.getTitle())
                                            .dataUrl(subscription.getHtmlUrl())
                                            .iconUrl(subscription.getIconUrl())
                                            .uniqueId(subscription.getId())
                                            .categories(categoryList));
                                }


                                //remove non existing sources
                                List<Source> allSources = Source.getAll(context);
                                for (Source source : allSources) {
                                    boolean found = false;
                                    for (Subscription subscription : response.getSubscriptions()) {
                                        if (source.getUniqueId().equals(subscription.getId())) {
                                            found = true;
                                            break;
                                        }
                                    }

                                    if (!found) {
                                        source.delete(context);
                                    }

                                }


                                if (listener != null) listener.onprogressChanged(25);

                                Source.multipleSave(context, sources);

                                if (listener != null) {
                                    listener.onFinished();
                                }


                            }
                        });


                    }
                });

            }
        });

    }

    /**
     * Get the category for sources having no category on the server.
     * Palabre doesn't show sources without category so we create a fake one named "Uncategorized"
     * @param context the Context to be used
     * @return the Uncategorized category
     */
    public static Category getUncatCategory(Context context) {
        Category uncat = Category.getByUniqueId(context, UNCATEGORIZED_ID);
        //If the Uncategorized category doesn't exist, create it
        if (uncat == null) {
            uncat = new Category()
                    .title(context.getResources().getString(R.string.subscriptions))
                    .uniqueId("subscriptions");
            uncat.save(context);
        }
        return uncat;
    }

    /**
     * Called when articles are read/unread in Palabre and need to be updated on the server
     * @param articles List of read article unique ids
     * @param value the read state
     */
    @Override
    protected void onReadArticles(List<String> articles, boolean value) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Reading articles: " + articles.size() + " => " + value);
        if (value) {
            InoreaderService.getInstance(InoreaderExtension.this).markAsRead(articles);
        } else {

            InoreaderService.getInstance(InoreaderExtension.this).markAsUnread(articles);
        }
    }

    /**
     * Called when articles are saved/unsaved in Palabre and need to be updated on the server
     * @param articles List of saved article unique ids
     * @param value the saved state
     */
    @Override
    protected void onSavedArticles(List<String> articles, boolean value) {
        if (value) {
            InoreaderService.getInstance(InoreaderExtension.this).markAsSaved(articles);
        } else {

            InoreaderService.getInstance(InoreaderExtension.this).markAsUnsaved(articles);
        }
    }

    /**
     * Transform an Item to an article and add it to a list
     * @param userId the user ID to check for read/saved states
     * @param savedSources  the complete list of sources (cached for better performances)
     * @param articles the list of article to be used
     * @param item the item that will be added as an Article
     */
    public void addArticleToList(String userId, List<Source> savedSources, List<Article> articles, Item item) {
        long sourceId = -1;
        for (Source source : savedSources) {
            if (source.getUniqueId().equals(item.getOrigin().getStreamId())) {
                sourceId = source.getId();
                break;
            }
        }

        //Search for an image
        String image = "";
        Document doc = Jsoup.parse(item.getSummary().getContent());
        Elements imgs = doc.body().getElementsByTag("img");
        if (imgs.size() > 0) {
            image = imgs.get(0).attr("src");
        }


        articles.add(new Article()
                .uniqueId(item.getId())
                .title(item.getTitle())
                .author(item.getAuthor())
                .content(item.getSummary().getContent())
                .linkUrl(item.getCanonical().get(0).getHref())
                .date(new Date(item.getCrawlTimeMsec()))
                .read(item.getCategories().contains("user/" + userId + "/state/com.google/read"))
                .saved(item.getCategories().contains("user/" + userId + "/state/com.google/starred"))
                .image(image)
                .sourceId(sourceId));
    }

    /**
     * Listener used to send the result and progress of category and source refresh
     */
    public interface OnCategoryAndSourceRefreshed {
        void onFinished();

        void onFailure(RetrofitError retrofitError);

        void onprogressChanged(int progress);
    }
}