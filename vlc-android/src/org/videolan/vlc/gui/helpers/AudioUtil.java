/*****************************************************************************
 * AudioUtil.java
 *****************************************************************************
 * Copyright © 2011-2012 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/
package org.videolan.vlc.gui.helpers;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.BitmapFactory;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.MediaStore;
import androidx.annotation.WorkerThread;
import androidx.fragment.app.FragmentActivity;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.videolan.libvlc.util.AndroidUtil;
import org.videolan.medialibrary.media.MediaWrapper;
import org.videolan.vlc.BuildConfig;
import org.videolan.vlc.R;
import org.videolan.vlc.VLCApplication;
import org.videolan.vlc.media.MediaUtils;
import org.videolan.vlc.util.AndroidDevices;
import org.videolan.vlc.util.HttpImageLoader;
import org.videolan.vlc.util.MurmurHash;
import org.videolan.vlc.util.Permissions;
import org.videolan.vlc.util.Util;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class AudioUtil {
    public final static String TAG = "VLC/AudioUtil";

    /**
     * Cache directory (/sdcard/Android/data/...)
     */
    private static String CACHE_DIR = null;
    /**
     * VLC embedded art storage location
     */
    private static AtomicReference<String> ART_DIR = new AtomicReference<>();
    /**
     * Cover caching directory
     */
    private static AtomicReference<String> COVER_DIR = new AtomicReference<>();
//    /**
//     * User-defined playlist storage directory
//     */
//    public static AtomicReference<String> PLAYLIST_DIR = new AtomicReference<>();

    public static void setRingtone(MediaWrapper song, FragmentActivity context){
        if (!Permissions.canWriteSettings(context)) {
            Permissions.checkWriteSettingsPermission(context, Permissions.PERMISSION_SYSTEM_RINGTONE);
            return;
        }
        File newringtone = AndroidUtil.UriToFile(song.getUri());
        if(newringtone == null || !newringtone.exists()) {
            Toast.makeText(context.getApplicationContext(),context.getString(R.string.ringtone_error), Toast.LENGTH_SHORT).show();
            return;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DATA, newringtone.getAbsolutePath());
        values.put(MediaStore.MediaColumns.TITLE, song.getTitle());
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/*");
        values.put(MediaStore.Audio.Media.ARTIST, song.getArtist());
        values.put(MediaStore.Audio.Media.IS_RINGTONE, true);
        values.put(MediaStore.Audio.Media.IS_NOTIFICATION, false);
        values.put(MediaStore.Audio.Media.IS_ALARM, false);
        values.put(MediaStore.Audio.Media.IS_MUSIC, false);

        Uri uri = MediaStore.Audio.Media.getContentUriForPath(newringtone.getAbsolutePath());
        Uri newUri;
        try {
            context.getContentResolver().delete(uri, MediaStore.MediaColumns.DATA + "=\"" + newringtone.getAbsolutePath() + "\"", null);
            newUri = context.getContentResolver().insert(uri, values);
            RingtoneManager.setActualDefaultRingtoneUri(
                    context.getApplicationContext(),
                    RingtoneManager.TYPE_RINGTONE,
                    newUri
            );
        } catch(Exception e) {
            Log.e(TAG, "error setting ringtone", e);
            Toast.makeText(context.getApplicationContext(),
                    context.getString(R.string.ringtone_error),
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(
                context.getApplicationContext(),
                context.getString(R.string.ringtone_set, song.getTitle()),
                Toast.LENGTH_SHORT)
                .show();

    }

    @SuppressLint("NewApi")
    @WorkerThread
    public static void prepareCacheFolder(final Context context) {
        try {
            if (AndroidDevices.hasExternalStorage() && context.getExternalCacheDir() != null)
                CACHE_DIR = context.getExternalCacheDir().getPath();
            else
                CACHE_DIR = AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + "/Android/data/" + BuildConfig.APPLICATION_ID + "/cache";
        } catch (Exception|ExceptionInInitializerError e) { // catch NPE thrown by getExternalCacheDir()
            CACHE_DIR = AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY + "/Android/data/" + BuildConfig.APPLICATION_ID + "/cache";
        }
        ART_DIR.set(CACHE_DIR + "/art/");
        COVER_DIR.set(CACHE_DIR + "/covers/");
        for(String path : Arrays.asList(ART_DIR.get(), COVER_DIR.get())) {
            File file = new File(path);
            if (!file.exists())
                file.mkdirs();
        }
    }

    public static void clearCacheFolders() {
        for(String path : Arrays.asList(ART_DIR.get(), COVER_DIR.get())) {
            File file = new File(path);
            if (file.exists())
                deleteContent(file, false);
        }
    }

    private static void deleteContent(File dir, boolean deleteDir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null && files.length > 0) {
                for (File file : files) {
                    deleteContent(file, true);
                }
            }
        }
        if (deleteDir)
            dir.delete();
    }

    private static String getCoverFromMediaStore(Context context, MediaWrapper media) {
        final String album = media.getAlbum();
        if (album == null)
            return null;
        ContentResolver contentResolver = context.getContentResolver();
        Uri uri = android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI;
        Cursor cursor = contentResolver.query(uri, new String[]{
                        MediaStore.Audio.Albums.ALBUM,
                        MediaStore.Audio.Albums.ALBUM_ART},
                MediaStore.Audio.Albums.ALBUM + " LIKE ?",
                new String[]{album}, null);
        if (cursor == null) {
            // do nothing
        } else if (!cursor.moveToFirst()) {
            // do nothing
            cursor.close();
        } else {
            int titleColumn = cursor.getColumnIndex(android.provider.MediaStore.Audio.Albums.ALBUM_ART);
            String albumArt = cursor.getString(titleColumn);
            cursor.close();
            return albumArt;
        }
        return null;
    }

    private static String getCoverFromVlc(Context context, MediaWrapper media) throws NoSuchAlgorithmException, UnsupportedEncodingException {
        String artworkURL = media.getArtworkURL();
        if (artworkURL != null && artworkURL.startsWith("file://")) {
            return Uri.decode(artworkURL).replace("file://", "");
        } else if(artworkURL != null && artworkURL.startsWith("attachment://")) {
            // Decode if the album art is embedded in the file
            String mArtist = MediaUtils.INSTANCE.getMediaArtist(context, media);
            String mAlbum = MediaUtils.INSTANCE.getMediaAlbum(context, media);

            /* Parse decoded attachment */
            if( mArtist.length() == 0 || mAlbum.length() == 0 ||
                    mArtist.equals(VLCApplication.getAppContext().getString(R.string.unknown_artist)) ||
                    mAlbum.equals(VLCApplication.getAppContext().getString(R.string.unknown_album)) )
            {
                /* If artist or album are missing, it was cached by title MD5 hash */
                MessageDigest md = MessageDigest.getInstance("MD5");
                byte[] binHash = md.digest((artworkURL + media.getTitle()).getBytes("UTF-8"));
                /* Convert binary hash to normal hash */
                BigInteger hash = new BigInteger(1, binHash);
                String titleHash = hash.toString(16);
                while(titleHash.length() < 32) {
                    titleHash = "0" + titleHash;
                }
                /* Use generated hash to find art */
                artworkURL = ART_DIR.get() + "/arturl/" + titleHash + "/art.png";
            } else {
                /* Otherwise, it was cached by artist and album */
                artworkURL = ART_DIR.get() + "/artistalbum/" + mArtist + "/" + mAlbum + "/art.png";
            }

            return artworkURL;
        }
        return null;
    }

    private static String getCoverFromFolder(MediaWrapper media) {
        File f = AndroidUtil.UriToFile(media.getUri());
        if (f == null)
            return null;

        File folder = f.getParentFile();
        if (folder == null)
            return null;

        final String[] imageExt = { ".png", ".jpeg", ".jpg"};
        final String[] coverImages = {
                "Folder.jpg",           /* Windows */
                "AlbumArtSmall.jpg",    /* Windows */
                "AlbumArt.jpg",         /* Windows */
                "Album.jpg",
                ".folder.png",          /* KDE?    */
                "cover.jpg",            /* rockbox */
                "thumb.jpg"
        };

        /* Find the path without the extension  */
        int index = f.getName().lastIndexOf('.');
        if (index > 0) {
            final String name = f.getName().substring(0, index);
            final String ext  = f.getName().substring(index);
            final File[] files = folder.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String filename) {
                    return filename.startsWith(name) && Arrays.asList(imageExt).contains(ext);
                }
            });
            if (files != null && files.length > 0)
                return files[0].getAbsolutePath();
        }

        /* Find the classic cover Images */
        if ( folder.listFiles() != null) {
            for (File file : folder.listFiles()) {
                for (String str : coverImages) {
                    if (file.getAbsolutePath().endsWith(str))
                        return file.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String getCoverCachePath(Context context, MediaWrapper media, int width) {
        final int hash = MurmurHash.hash32(MediaUtils.INSTANCE.getMediaArtist(context, media) + MediaUtils.INSTANCE.getMediaAlbum(context, media));
        return COVER_DIR.get() + (hash >= 0 ? "" + hash : "m" + (-hash)) + "_" + width;
    }

    private static Bitmap getCoverFromMemCache(Context context, MediaWrapper media, int width) {
        Bitmap cover = null;
        final BitmapCache cache = BitmapCache.getInstance();
        if (media != null && media.getArtist() != null && media.getAlbum() != null) {
            cover = cache.getBitmapFromMemCache(getCoverCachePath(context, media, width));
        }
        if (cover == null && media != null && !TextUtils.isEmpty(media.getArtworkURL()) && media.getArtworkURL().startsWith("http")) {
            cover = cache.getBitmapFromMemCache(media.getArtworkURL());
        }
        return cover;
    }

    @SuppressLint("NewApi")
    public synchronized static Bitmap getCover(Context context, MediaWrapper media, int width) {
        BitmapCache cache = BitmapCache.getInstance();
        String coverPath = null;
        Bitmap cover = null;
        String cachePath = null;
        File cacheFile = null;

        if (width <= 0) {
            Log.e(TAG, "Invalid cover width requested");
            return null;
        }

        // if external storage is not available, skip covers to prevent slow audio browsing
        if (!AndroidDevices.hasExternalStorage())
            return null;

        try {
            // try to load from cache
            if (media.getArtist() != null && media.getAlbum() != null) {
                cachePath = getCoverCachePath(context, media, width);

                // try to get the cover from the LRUCache first
                cover = cache.getBitmapFromMemCache(cachePath);
                if (cover != null)
                    return cover;

                // try to get the cover from the storage cache
                cacheFile = new File(cachePath);
                if (cacheFile.exists()) {
                    if (cacheFile.length() > 0)
                        coverPath = cachePath;
                }
            } else

            // try to get it from VLC
            if (coverPath == null || !cacheFile.exists())
                coverPath = getCoverFromVlc(context, media);

            // try to get the cover from android MediaStore
            if (coverPath == null || !(new File(coverPath)).exists())
                coverPath = getCoverFromMediaStore(context, media);

            // no found yet, looking in folder
            if (coverPath == null || !(new File(coverPath)).exists())
                coverPath = getCoverFromFolder(media);

            // read (and scale?) the bitmap
            cover = readCoverBitmap(coverPath, width);

            // store cover into both cache
            if (cachePath != null) {
                writeBitmap(cover, cachePath);
                cache.addBitmapToMemCache(cachePath, cover);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return cover;
    }

    private static void writeBitmap(Bitmap bitmap, String path) throws IOException {
        OutputStream out = null;
        try {
            File file = new File(path);
            if (file.exists() && file.length() > 0)
                return;
            out = new BufferedOutputStream(new FileOutputStream(file), 4096);
            if (bitmap != null)
                bitmap.compress(CompressFormat.JPEG, 90, out);
        } catch (Exception e) {
            Log.e(TAG, "writeBitmap failed : "+ e.getMessage());
        } finally {
            Util.close(out);
        }
    }

    @WorkerThread
    public static Bitmap readCoverBitmap(String path, int width) {
        if (path == null) return null;
        if (path.startsWith("http")) return HttpImageLoader.downloadBitmap(path);
        if (path.startsWith("file")) path = path.substring(7);
        Bitmap cover = null;
        BitmapFactory.Options options = new BitmapFactory.Options();

        /* Get the resolution of the bitmap without allocating the memory */
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);

        if (options.outWidth > 0 && options.outHeight > 0) {
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;

            // Find the best decoding scale for the bitmap
            if (width > 0) {
                while (options.outWidth / options.inSampleSize > width)
                    options.inSampleSize = options.inSampleSize * 2;
            }

            // Decode the file (with memory allocation this time)
            cover = BitmapFactory.decodeFile(path, options);
            BitmapCache.getInstance().addBitmapToMemCache(path, cover);
        }
        return cover;
    }

    public static Bitmap getCover(Context context, List<MediaWrapper> list, int width, boolean fromMemCache) {
        Bitmap cover = null;
        LinkedList<String> testedAlbums = new LinkedList<String>();
        for (MediaWrapper media : list) {
            /* No list cover is artist or album are null */
            if (media.getAlbum() == null || media.getArtist() == null)
                continue;
            if (testedAlbums.contains(media.getAlbum()))
                continue;

            cover = fromMemCache ? AudioUtil.getCoverFromMemCache(context, media, width) : AudioUtil.getCover(context, media, width);
            if (cover != null)
                break;
            else if (media.getAlbum() != null)
                testedAlbums.add(media.getAlbum());
        }
        return cover;
    }

    public static Bitmap getCoverFromMemCache(Context context, List<MediaWrapper> list, int width) {
        return getCover(context, list, width, true);
    }

    public static Bitmap getCover(Context context, List<MediaWrapper> list, int width) {
        return getCover(context, list, width, false);
    }
}
