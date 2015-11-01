/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.paug.droidcon2015.sync;

import android.content.Context;
import android.text.TextUtils;

import com.google.gson.Gson;
import fr.paug.droidcon2015.BuildConfig;
import fr.paug.droidcon2015.io.model.DataManifest;
import fr.paug.droidcon2015.util.HashUtils;
import fr.paug.droidcon2015.util.IOUtils;
import fr.paug.droidcon2015.util.TimeUtils;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.HashSet;
import java.util.List;

import com.turbomanage.httpclient.BasicHttpClient;
import com.turbomanage.httpclient.ConsoleRequestLogger;
import com.turbomanage.httpclient.HttpResponse;
import com.turbomanage.httpclient.RequestLogger;

import fr.paug.droidcon2015.util.LogUtils;

/**
 * Helper class that fetches conference data from the remote server.
 */
public class RemoteConferenceDataFetcher {
    private static final String TAG = LogUtils.makeLogTag(SyncHelper.class);

    // The directory under which we cache our downloaded files
    private static String CACHE_DIR = "data_cache";

    private Context mContext = null;

    // name of URL override file used for debug purposes
    private static final String URL_OVERRIDE_FILE_NAME = "iosched_manifest_url_override.txt";

    // URL of the remote manifest file
    private String mManifestUrl = null;

    // timestamp of the manifest file on the server
    private String mServerTimestamp = null;

    // the set of cache files we have used -- we use this for cache cleanup.
    private HashSet<String> mCacheFilesToKeep = new HashSet<String>();

    // total # of bytes downloaded (approximate)
    private long mBytesDownloaded = 0;

    // total # of bytes read from cache hits (approximate)
    private long mBytesReadFromCache = 0;

    public RemoteConferenceDataFetcher(Context context) {
        mContext = context;
        mManifestUrl = getManifestUrl();
    }

    /**
     * Fetches data from the remote server.
     *
     * @param refTimestamp The timestamp of the data to use as a reference; if the remote data
     *                     is not newer than this timestamp, no data will be downloaded and
     *                     this method will return null.
     *
     * @return The data downloaded, or null if there is no data to download
     * @throws IOException if an error occurred during download.
     */
    public String[] fetchConferenceDataIfNewer(String refTimestamp) throws IOException {
        if (TextUtils.isEmpty(mManifestUrl)) {
            LogUtils.LOGW(TAG, "Manifest URL is empty (remote sync disabled!).");
            return null;
        }

        BasicHttpClient httpClient = new BasicHttpClient();
        httpClient.setRequestLogger(mQuietLogger);

        // Only download if data is newer than refTimestamp
        // Cloud Storage is very picky with the If-Modified-Since format. If it's in a wrong
        // format, it refuses to serve the file, returning 400 HTTP error. So, if the
        // refTimestamp is in a wrong format, we simply ignore it. But pay attention to this
        // warning in the log, because it might mean unnecessary data is being downloaded.
        if (!TextUtils.isEmpty(refTimestamp)) {
            if (TimeUtils.isValidFormatForIfModifiedSinceHeader(refTimestamp)) {
                httpClient.addHeader("If-Modified-Since", refTimestamp);
            } else {
                LogUtils.LOGW(TAG, "Could not set If-Modified-Since HTTP header. Potentially downloading " +
                        "unnecessary data. Invalid format of refTimestamp argument: " + refTimestamp);
            }
        }

        return processManifest();
    }

    // Returns the timestamp of the data downloaded from the server
    public String getServerDataTimestamp() {
        return mServerTimestamp;
    }

    /**
     * Returns the remote manifest file's URL. This is stored as a resource in the app,
     * but can be overriden by a file in the filesystem for debug purposes.
     * @return The URL of the remote manifest file.
     */
    private String getManifestUrl() {

        String manifestUrl = BuildConfig.SERVER_MANIFEST_ENDPOINT;

        // check for an override file
        File urlOverrideFile = new File(mContext.getFilesDir(), URL_OVERRIDE_FILE_NAME);
        if (urlOverrideFile.exists()) {
            try {
                String overrideUrl = IOUtils.readFileAsString(urlOverrideFile).trim();
                LogUtils.LOGW(TAG, "Debug URL override active: " + overrideUrl);
                return overrideUrl;
            } catch (IOException ex) {
                return manifestUrl;
            }
        } else {
            return manifestUrl;
        }
    }

    /**
     * Fetches a file from the cache/network, from an absolute or relative URL. If the
     * file is available in our cache, we read it from there; if not, we will
     * download it from the network and cache it.
     *
     * @param url The URL to fetch the file from. The URL may be absolute or relative; if
     *            relative, it will be considered to be relative to the manifest URL.
     * @return The contents of the file.
     * @throws IOException If an error occurs.
     */
    private String fetchFile(String url) throws IOException {
        // If this is a relative url, consider it relative to the manifest URL
        if (!url.contains("://")) {
            if (TextUtils.isEmpty(mManifestUrl) || !mManifestUrl.contains("/")) {
                LogUtils.LOGE(TAG, "Could not build relative URL based on manifest URL.");
                return null;
            }
            int i = mManifestUrl.lastIndexOf('/');
            url = mManifestUrl.substring(0, i) + "/" + url;
        }

        LogUtils.LOGD(TAG, "Attempting to fetch: " + sanitizeUrl(url));

        // Check if we have it in our cache first
        String body = null;
        try {
            body = loadFromCache(url);
            if (!TextUtils.isEmpty(body)) {
                // cache hit
                mBytesReadFromCache += body.getBytes().length;
                mCacheFilesToKeep.add(getCacheKey(url));
                return body;
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            LogUtils.LOGE(TAG, "IOException getting file from cache.");
            // proceed anyway to attempt to download it from the network
        }

        BasicHttpClient client = new BasicHttpClient();
        client.setRequestLogger(mQuietLogger);

        // We don't have the file on cache, so download it
        LogUtils.LOGD(TAG, "Cache miss. Downloading from network: " + sanitizeUrl(url));
        HttpResponse response = client.get(url, null);

        if (response == null) {
            throw new IOException("Request for URL " + sanitizeUrl(url) + " returned null response.");
        }

        LogUtils.LOGD(TAG, "HTTP response " + response.getStatus());
        if (response.getStatus() == HttpURLConnection.HTTP_OK) {
            body = response.getBodyAsString();
            if (TextUtils.isEmpty(body)) {
                throw new IOException("Got empty response when attempting to fetch " +
                        sanitizeUrl(url) + url);
            }
            LogUtils.LOGD(TAG, "Successfully downloaded from network: " + sanitizeUrl(url));
            mBytesDownloaded += body.getBytes().length;
            writeToCache(url, body);
            mCacheFilesToKeep.add(getCacheKey(url));
            return body;
        } else {
            LogUtils.LOGE(TAG, "Failed to fetch from network: " + sanitizeUrl(url));
            throw new IOException("Request for URL " + sanitizeUrl(url) +
                    " failed with HTTP error " + response.getStatus());
        }
    }

    /**
     * Returns the cache file where we store our cache of the response of the given URL.
     * @param url The URL for which to return the cache file.
     * @return The cache file.
     */
    private File getCacheFile(String url) {
        String cacheKey = getCacheKey(url);
        return new File(mContext.getCacheDir() + File.separator + CACHE_DIR + File.separator +
                cacheKey);
    }

    // Creates the cache directory, if it doesn't exist yet
    private void createCacheDir() throws IOException {
        File dir = new File(mContext.getCacheDir() + File.separator + CACHE_DIR);
        if (!dir.exists() && !dir.mkdir()) {
            throw new IOException("Failed to mkdir: " + dir);
        }
    }


    /**
     * Loads our cached content corresponding to the given URL.
     * @param url The URL for which to load the cached response.
     * @return The cached response corresponding to the URL; or null if the given URL
     * does not exist in our cache.
     * @throws IOException If there is an error reading the cache.
     */
    private String loadFromCache(String url) throws IOException {
        String cacheKey = getCacheKey(url);
        File cacheFile = getCacheFile(url);
        if (cacheFile.exists()) {
            LogUtils.LOGD(TAG, "Cache hit " + cacheKey + " for " + sanitizeUrl(url));
            return IOUtils.readFileAsString(cacheFile);
        } else {
            LogUtils.LOGD(TAG, "Cache miss " + cacheKey + " for " + sanitizeUrl(url));
            return null;
        }
    }

    /**
     * Writes a file to the cache.
     * @param url The URL from which the contents were retrieved.
     * @param body The contents retrieved from the given URL.
     * @throws IOException If there is a problem writing the file.
     */
    private void writeToCache(String url, String body) throws IOException {
        String cacheKey = getCacheKey(url);
        File cacheFile = getCacheFile(url);
        createCacheDir();
        IOUtils.writeToFile(body, cacheFile);
        LogUtils.LOGD(TAG, "Wrote to cache " + cacheKey + " --> " + sanitizeUrl(url));
    }

    /**
     * Returns the cache key to be used to store the given URL. The cache key is the
     * file name under which the contents of the URL are stored.
     * @param url The URL.
     * @return The cache key (guaranteed to be a valid filename)
     */
    private String getCacheKey(String url) {
        return HashUtils.computeWeakHash(url.trim()) + String.format("%04x", url.length());
    }

    // Sanitize a URL for logging purposes (only the last component is left visible).
    private String sanitizeUrl(String url) {
        int i = url.lastIndexOf('/');
        if (i >= 0 && i < url.length()) {
            return url.substring(0, i).replaceAll("[A-za-z]", "*") +
                    url.substring(i);
        }
        else return url.replaceAll("[A-za-z]", "*");
    }

    private static final String MANIFEST_FORMAT = "iosched-json-v1";

    /**
     * Process the data manifest and download data files referenced from it.
     * @return The contents of the set of files referenced from the manifest, or null
     * if none could be retrieved.
     * @throws IOException If an error occurs while retrieving information.
     */
    private String[] processManifest() throws IOException {

        DataManifest manifest = new DataManifest();


        LogUtils.LOGD(TAG, "Manifest lists " + manifest.data_files.length + " data files.");
        String[] jsons = new String[manifest.data_files.length];
        for (int i = 0; i < manifest.data_files.length; i++) {
            String url = manifest.data_files[i];
            LogUtils.LOGD(TAG, "Processing data file: " + sanitizeUrl(url));
            jsons[i] = fetchFile(url);
            if (TextUtils.isEmpty(jsons[i])) {
                LogUtils.LOGE(TAG, "Failed to fetch data file: " + sanitizeUrl(url));
                throw new IOException("Failed to fetch data file " + sanitizeUrl(url));
            }
        }

        LogUtils.LOGD(TAG, "Got " + jsons.length + " data files.");
        cleanUpCache();
        return jsons;
    }

    // Delete unnecessary files from our cache
    private void cleanUpCache() {
        LogUtils.LOGD(TAG, "Starting cache cleanup, " + mCacheFilesToKeep.size() + " URLs to keep.");
        File dir = new File(mContext.getCacheDir() + File.separator + CACHE_DIR);
        if (!dir.exists()) {
            LogUtils.LOGD(TAG, "Cleanup complete (there is no cache).");
            return;
        }

        int deleted = 0, kept = 0;
        for (File file : dir.listFiles()) {
            if (mCacheFilesToKeep.contains(file.getName())) {
                LogUtils.LOGD(TAG, "Cache cleanup: KEEEPING " + file.getName());
                ++kept;
            } else {
                LogUtils.LOGD(TAG, "Cache cleanup: DELETING " + file.getName());
                file.delete();
                ++deleted;
            }
        }

        LogUtils.LOGD(TAG, "End of cache cleanup. " + kept + " files kept, " + deleted + " deleted.");
    }

    public long getTotalBytesDownloaded() {
        return mBytesDownloaded;
    }

    public long getTotalBytesReadFromCache() {
        return mBytesReadFromCache;
    }

    private String getLastModified(HttpResponse resp) {
        if (!resp.getHeaders().containsKey("Last-Modified")) {
            return "";
        }

        List<String> s = resp.getHeaders().get("Last-Modified");
        return s.isEmpty() ? "" : s.get(0);
    }

    /**
     * A type of ConsoleRequestLogger that does not log requests and responses.
     */
    private RequestLogger mQuietLogger = new ConsoleRequestLogger(){
        @Override
        public void logRequest(HttpURLConnection uc, Object content) throws IOException { }

        @Override
        public void logResponse(HttpResponse res) { }
    };


}
